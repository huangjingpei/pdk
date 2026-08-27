# 多业务 `bizId / appId` 隔离改造方案

## 1. 结论

建议增加独立的“业务”主数据，并明确区分两个标识：

- `bizId`：服务端内部业务主键，作为数据库外键和数据隔离边界。
- `appId`：客户端公开携带的应用标识，用于把客户端请求路由到对应业务。现有拼多多客户端固定为 `appId=1`，映射到 `bizId=1`。

二者当前是一对一关系，但不应在代码中当作同一个字段使用。`appId` 是公开标识，不是密码或授权凭证；真正的身份认证仍由用户密码、设备 UUID、登录 Token 和服务端业务归属共同完成。

首期明确支持三个业务：

| bizId | appId | bizCode | 展示名称 | 初始后台状态 |
| ---: | ---: | --- | --- | --- |
| 1 | 1 | `PDD` | 拼多多业务 | `ACTIVE`，兼容现有系统 |
| 2 | 2 | `ZHIBO_AI` | zhibo-ai | `DISABLED`，管理员确认配置后启用 |
| 3 | 3 | `ZHIBO_LIVE` | zhibo-live | `DISABLED`，管理员确认配置后启用 |

这里的 appId 2、3 是推荐固定值，应在产生正式数据前最终确认；一旦客户端发布并产生业务数据就不再修改。

每个业务配置一种注册模式：

| 注册模式 | 含义 | 客户端行为 |
| --- | --- | --- |
| `SELF_SERVICE` | 支持用户自助注册 | 展示注册入口，允许发送短信验证码和注册，可按业务配置试用 |
| `ADMIN_ONLY` | 只允许管理员预创建账号 | 客户端隐藏注册入口；管理员配置手机号和初始密码后交付用户 |

注册模式只描述“如何创建账号”，业务整体开关由 `pdk_business.status=ACTIVE/DISABLED` 单独控制，不能把 `DISABLED` 同时当作注册模式，否则会混淆“业务关闭”和“禁止自助注册”两个概念。

`ADMIN_ONLY` 用户仍使用统一客户端登录接口，携带 `appId + 手机号 + 密码 + 设备UUID`。管理员创建时设备 UUID 可以为空，首次成功登录时绑定；已绑定后继续沿用现有单设备校验与管理员解绑机制。

## 2. 当前项目扫描结论

当前项目实际上已经具备管理员预创建账号的基础能力：

- 客户端自助注册和登录位于 `ClientAuthController`。
- 管理员手工创建用户位于 `AdminUserController.POST /api/v1/admin/user`。
- `AdminCreateUserDTO` 已包含手机号、初始密码和可选设备 UUID。
- 用户、套餐、卡密、小号池、独占分配、消费流水目前都没有业务字段。
- `pdk_user.phone` 当前全局唯一，查询也普遍仅按手机号进行。
- `DeviceBindingService` 的 Redis Key 当前为 `pdk:device:bind:{phone}`。
- `ResourceLeaseService` 的租约仅记录手机号，没有记录业务。
- `TokenPoolMapper` 从全局小号池选取资源，没有业务过滤。
- 管理端已有用户、套餐、卡密、调度、销售和财务页面，但没有业务管理页面和业务筛选。
- PyQt/SDK 请求当前没有统一携带 `appId`。

因此不能只在用户表增加一个 `appId` 展示字段。若调度池、套餐、卡密、Redis Key 和流水不同时隔离，会出现业务 A 用户领取业务 B 小号、同手机号跨客户端互踢、跨业务卡密激活和统计串账。

另外发现一个现有约束不一致：`AdminCreateUserDTO` 允许 6～32 位密码，而 `ClientLoginDTO` 要求 8～64 位。管理员创建 6～7 位密码时客户端永远无法登录。多业务改造时应统一为 8～64 位。

## 3. 核心数据模型

### 3.1 新增业务表

建议新增 `pdk_business`：

```sql
CREATE TABLE pdk_business (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'bizId，内部业务主键',
    app_id BIGINT NOT NULL UNIQUE COMMENT '客户端公开应用ID',
    biz_code VARCHAR(32) NOT NULL UNIQUE COMMENT '稳定业务编码，如 PDD',
    biz_name VARCHAR(64) NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    registration_mode VARCHAR(20) NOT NULL COMMENT 'SELF_SERVICE, ADMIN_ONLY',
    trial_enabled TINYINT NOT NULL DEFAULT 0,
    trial_duration_hours INT NOT NULL DEFAULT 0,
    trial_account_count INT NOT NULL DEFAULT 0,
    trial_calls_per_account INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, DISABLED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

初始化三个业务：

```text
bizId=1
appId=1
bizCode=PDD
bizName=拼多多业务
description=现有拼多多采集与分发业务
registrationMode=SELF_SERVICE（或上线时由管理员确认）
status=ACTIVE

bizId=2
appId=2
bizCode=ZHIBO_AI
bizName=zhibo-ai
registrationMode=ADMIN_ONLY（建议初始值，可在后台调整）
status=DISABLED

bizId=3
appId=3
bizCode=ZHIBO_LIVE
bizName=zhibo-live
registrationMode=ADMIN_ONLY（建议初始值，可在后台调整）
status=DISABLED
```

业务已产生用户后，`appId` 和 `bizCode` 不允许修改，只允许修改名称、描述、注册策略、试用参数和状态。

### 3.2 需要增加 `biz_id` 的表

| 表 | 是否必须 | 原因 |
| --- | --- | --- |
| `pdk_user` | 必须 | 用户账号归属和登录隔离 |
| `pdk_sms_verification` | 必须 | 验证码频控与消费不能跨业务 |
| `pdk_invitation_code` | 必须 | 邀请码必须属于具体业务 |
| `pdk_package_plan` | 必须 | 套餐模板不能跨业务使用 |
| `pdk_card_key` | 必须 | 卡密激活时执行业务一致性校验 |
| `pdk_token_pool` | 必须 | 小号资源池按业务物理隔离 |
| `pdk_account_assignment` | 建议冗余 | 提高调度查询和审计隔离的可靠性 |
| `pdk_dispatch_log` | 必须 | 消费记录、统计和故障分析按业务查询 |
| `pdk_financial_income` | 必须 | 销售与续费财务按业务核算 |
| `pdk_company_expense` | 建议可空 | 支持业务专属成本；`NULL` 表示平台公共成本 |
| `pdk_admin_audit_log` | 建议可空 | 记录管理员操作所影响的业务 |
| `pdk_system_config` | 视配置而定 | 平台配置保留全局；业务配置优先放 `pdk_business` 或单独覆盖表 |

`pdk_user_credential` 不必再加 `biz_id`，因为它通过全局唯一 `user_id` 关联用户；登录查询必须先用 `appId` 解析 `bizId`，再按 `(biz_id, phone)` 查询用户。

### 3.3 用户唯一性

将用户唯一约束从：

```text
UNIQUE(phone)
```

调整为：

```text
UNIQUE(biz_id, phone)
```

这样同一个手机号可以分别拥有业务 A 客户端账号和业务 B 客户端账号，密码、设备绑定、套餐和次数相互独立。`user.id` 仍保持全局唯一，Sa-Token 继续以用户 ID 作为登录 ID，现有登录会话机制不需要重写。

不建议把一个 `pdk_user` 同时挂多个业务。那会导致设备、密码、状态、套餐和次数难以确定究竟属于哪个客户端，后续逻辑反而更复杂。

## 4. 请求上下文设计

新增统一的 `BusinessContextResolver`：

1. 从注册、登录、短信、卡密激活请求体读取 `appId`。
2. 从登录后的请求头读取 `X-PDK-App-ID`。
3. 查询并缓存 `appId -> Business`。
4. 校验业务存在且状态允许当前操作。
5. 将 `bizId/appId/business` 放入当前请求上下文。

新客户端所有请求必须携带 `appId`。为不影响现有拼多多客户端，建议设置一个兼容期：

- 请求未携带 `appId` 时临时按 `appId=1` 处理，并记录兼容日志和监控指标。
- 新版本客户端和 SDK 必须显式携带 `appId`。
- 完成客户端升级后，可通过配置关闭缺省值，缺少 `appId` 返回明确错误。

登录成功响应应返回：

```json
{
  "bizId": 1,
  "appId": 1,
  "businessName": "拼多多业务",
  "businessDescription": "现有拼多多采集与分发业务"
}
```

登录后的拦截器必须同时校验：

- Token 对应的用户存在且可用；
- 用户的 `bizId` 与 `X-PDK-App-ID` 解析出的业务一致；
- `X-PDK-Phone` 与当前用户一致；
- 设备 UUID 与当前用户绑定一致。

客户端传入的 `appId` 只能用于选择业务，不能覆盖 Token 中用户的真实业务归属。

## 5. 两种账号流程

### 5.1 支持自动注册的业务

1. 客户端调用公开业务信息接口，根据 `appId` 获取业务描述和注册策略。
2. 仅当 `registrationMode=SELF_SERVICE` 时显示注册页和发送验证码按钮。
3. 发送短信时提交 `appId + phone + purpose`。
4. 注册时提交 `appId + phone + smsCode + password + deviceId + invitationCode`。
5. 服务端校验业务策略、验证码业务归属、邀请码业务归属和 `(bizId, phone)` 唯一性。
6. 按该业务的试用配置创建权益；不能继续使用全局统一试用参数。

### 5.2 管理员预创建账号的业务

1. 管理员在用户页面选择业务，填写手机号和初始密码，可选填设备 UUID。
2. 服务端只允许在 `ADMIN_ONLY` 或允许后台建号的业务下创建。
3. 不发送注册短信、不自动发放试用，默认状态为 `ACTIVE`、套餐未开通、次数为 0。
4. 管理员线下把 `appId + 手机号 + 初始密码` 交付用户。
5. 用户登录客户端时提交 `appId + phone + password + deviceId`。
6. 若数据库设备 UUID 为空，首次成功登录原子绑定当前 UUID；否则必须一致。

建议为凭证增加 `must_change_password`，管理员创建时默认为 1。客户端首次登录后提示修改初始密码；是否强制可按业务配置。数据库和日志始终只保存 BCrypt 摘要，管理后台不能回显旧密码，只能重置为新临时密码。

`ADMIN_ONLY` 业务调用 `/sms/send` 的 `REGISTER` 用途或 `/register` 时应返回例如：

```text
40320 当前业务不支持自助注册，请联系管理员开通账号
```

## 6. API 调整

### 6.1 新增公开接口

```http
GET /api/v1/client/business/by-app/{appId}
```

仅返回可公开字段：`appId`、业务名称、业务描述、注册模式、是否展示注册入口、是否支持试用。不要返回内部密钥或运营配置。

### 6.2 客户端接口字段

| 接口 | 改造 |
| --- | --- |
| `/client/auth/sms/send` | 增加 `appId`，按业务校验自助注册策略和短信频控 |
| `/client/auth/register` | 增加 `appId`，仅 `SELF_SERVICE` 可调用 |
| `/client/auth/login` | 增加 `appId`，按 `(bizId, phone)` 鉴权 |
| `/client/auth/change-password` | 增加 `appId`，避免同手机号修改错误业务的密码 |
| `/card/activate` | 增加 `appId`，卡密、套餐、用户三者业务必须一致 |
| 所有登录后接口 | 增加 `X-PDK-App-ID`，由拦截器统一校验 |

建议错误码：

| 错误码 | 含义 |
| --- | --- |
| `40050` | appId 缺失或格式错误 |
| `40450` | appId 对应业务不存在 |
| `40320` | 当前业务不支持自助注册 |
| `40321` | 业务已停用 |
| `40106` | 登录会话与 appId 不一致 |
| `40051` | 卡密/套餐/用户业务不一致 |

## 7. 调度与 Redis 隔离

这是改造中最不能遗漏的部分。

### 7.1 设备绑定

当前 Redis Key：

```text
pdk:device:bind:{phone}
```

调整为：

```text
pdk:device:bind:{bizId}:{userId}
```

不能只增加 appId 但仍按手机号做 Key，否则同一手机号登录业务 A 和业务 B 会互相踢下线。

### 7.2 小号调度

`TokenPoolMapper.selectUnassignedHealthyForUpdate` 必须增加 `biz_id = ?` 条件；批量导入小号时必须先选择业务。业务 A 的用户、套餐和 assignment 只能分配业务 A 的小号。

### 7.3 短效租约

Redis 租约中增加 `bizId` 和 `userId`，消费租约时同时校验：

```text
traceId + bizId + userId
```

不能继续只校验手机号。消费流水写入 `pdk_dispatch_log.biz_id`，幂等键可以继续全局唯一。

## 8. 管理后台方案

### 8.1 新增“业务管理”页面

建议路由：`/business/manager`，仅超级管理员拥有 `business:view` 和 `business:edit` 权限。

列表字段：

- `bizId`
- `appId`
- 业务编码
- 业务名称
- 业务描述
- 注册模式
- 后台业务开关状态
- 当前部署是否包含该业务
- Handler 是否加载成功
- 最终有效状态及不可用原因
- 试用策略
- 用户数
- 套餐数
- 小号资源数/可用数
- 状态
- 创建时间

支持操作：创建业务、修改名称/描述、切换注册策略、修改试用配置、启停业务。已有数据的业务禁止删除和修改 `appId`，只能停用。当前部署未包含对应 Handler 时，后台仍展示该业务及历史数据，但“启用”按钮必须禁用并提示需要重新部署。

### 8.2 用户页面

用户列表增加：

- “业务”筛选器；
- “业务名称/描述”列（用户要求的业务描述列）；
- `appId` 列；
- “账号来源”列：`SELF_REGISTER` / `ADMIN_CREATED`；
- “首次登录需改密”状态。

新增用户弹窗必须先选择业务。选择 `ADMIN_ONLY` 业务时突出显示“管理员创建后线下交付账号”；选择 `SELF_SERVICE` 业务时仍允许管理员代建，但应记录来源为后台创建。

### 8.3 其他页面

套餐、卡密、小号池、消费记录、销售记录、财务和运营大盘都增加业务筛选和业务列。创建套餐、制卡、导入小号时业务为必填，且后续关联对象只能选择同一业务的数据。

PARTNER 账号只能看到自身所属业务的数据；SUPER_ADMIN 默认看全部，可按业务筛选。所有后端查询必须带业务权限条件，不能只依赖前端隐藏。

## 9. PyQt 与 SDK

每一个客户端构建应固定自己的 `appId`，不建议让普通用户在输入框里任意修改。开发调试版可以显示 appId 下拉框，生产版从构建配置读取。

启动流程：

1. 客户端用固定 `appId` 请求公开业务信息。
2. 窗口显示业务名称和业务描述。
3. `SELF_SERVICE` 显示登录、注册、卡密兑换、修改密码。
4. `ADMIN_ONLY` 隐藏注册和发送验证码，只显示登录、卡密兑换、修改密码，并提示“账号由管理员提供”。
5. 所有请求日志显示 `appId/bizId`、请求用途和预期结果，但密码、验证码、卡密和 Token 必须脱敏。

Python SDK、C++ SDK、易语言声明和 PyQt `PdkApiClient` 都应新增 appId 配置，并在登录后请求中自动附加 `X-PDK-App-ID`，避免每个业务调用方自行拼装而遗漏。

## 10. 兼容迁移方案

推荐分四阶段实施：

### 阶段一：建立业务主数据

- 新建 `pdk_business`，插入 `bizId=1/appId=1` 的拼多多业务。
- 各业务表增加可空 `biz_id`，将历史数据全部回填为 1。
- 接口开始接受 appId；旧客户端缺省 appId 暂映射为 1。

### 阶段二：代码查询全面加业务条件

- 登录改为 `(bizId, phone)`。
- 套餐、卡密、小号、assignment、流水、财务查询加入 `bizId`。
- Redis 设备 Key 和租约加入业务/用户维度。
- 管理端增加业务页面、筛选和业务描述列。

### 阶段三：约束收紧

- `biz_id` 改为非空。
- 删除 `phone` 全局唯一索引，增加 `(biz_id, phone)` 唯一索引。
- 增加卡密、套餐、小号相关业务复合索引。
- 新 SDK 和客户端强制发送 appId。

### 阶段四：关闭兼容模式

- 监控确认没有缺失 appId 的旧客户端请求。
- 关闭 `appId=1` 自动兜底。
- 缺少 appId 统一返回 `40050`。

## 11. Spring Boot 自动初始化要求

现有 `spring.sql.init.mode=always` 和 `schema-locations=classpath:schema-mysql.sql` 必须保留。多业务改造涉及新增表、增加列、替换唯一索引，不能只依赖新的 `CREATE TABLE IF NOT EXISTS`，因为它不会修改已存在的表。

建议在 `schema-mysql.sql` 中增加 `pdk_schema_migration` 版本记录，并用可重复执行的条件迁移完成：

1. 判断列/索引是否存在；
2. 不存在才执行 `ALTER TABLE`；
3. 回填历史数据为 `biz_id=1`；
4. 校验没有空值和跨业务脏数据；
5. 最后建立非空及唯一约束；
6. 记录迁移版本。

任何迁移失败仍应让 Spring Boot 启动失败，避免应用在部分业务表已隔离、部分未隔离的危险状态下运行。上线前必须对生产库做备份并在副本演练唯一索引替换。

## 12. 验收标准

至少覆盖以下自动化场景：

1. 不携带 appId 的旧拼多多客户端在兼容期开启时仍按 appId=1 工作。
2. 同一手机号可分别注册/创建在业务 A 和业务 B，密码及设备互不影响。
3. `ADMIN_ONLY` 业务无法发送注册短信、无法自助注册。
4. 管理员创建的账号可用 appId、手机号、密码和首次设备 UUID 登录。
5. 6～7 位管理员初始密码不再允许创建，登录与创建规则统一为 8～64 位。
6. 业务 A Token 不能用于携带业务 B appId 的请求。
7. 业务 A 用户不能激活业务 B 卡密、选择业务 B 套餐或领取业务 B 小号。
8. 同手机号同时登录业务 A 和业务 B不会互踢；同一业务内仍保持单设备约束。
9. 调度、扣次、故障替换、消费流水和财务记录均带正确 bizId。
10. 管理后台用户页显示 appId、业务名称/描述和账号来源，并可按业务筛选。
11. 停用业务后禁止新登录和新业务调用，历史流水仍可查询。
12. `schema-mysql.sql` 在空库和已有单业务库上均能重复启动执行。

## 13. 推荐实施顺序

优先顺序建议为：

1. `pdk_business`、业务解析器和数据库迁移；
2. 用户、注册、管理员建号、登录与设备 Redis Key；
3. 套餐、卡密、小号池、assignment 和调度租约；
4. 流水、财务、审计和报表；
5. 管理后台业务页及各页面业务筛选；
6. PyQt 与各语言 SDK；
7. 兼容监控、全链路测试和关闭 appId 缺省兜底。

其中第 1～3 步必须作为同一个上线版本交付，不能只改登录而不改小号调度，否则会形成跨业务资源泄漏。

## 14. `bizId / appId / bizCode` 的进一步定义

三个字段不能混用，建议采用以下约束：

| 字段 | 建议类型 | 是否对客户端公开 | 是否可修改 | 主要用途 |
| --- | --- | --- | --- | --- |
| `bizId` | `BIGINT` | 响应可返回，请求不信任 | 永不修改 | 数据库关联、索引、权限和数据隔离 |
| `appId` | `BIGINT` | 是，客户端固定携带 | 业务产生数据后不可修改 | 客户端识别、请求路由 |
| `bizCode` | `VARCHAR(32)` | 可展示，但不作为请求权威值 | 不可修改 | 服务端可读编码、日志、监控、策略实现选择 |

拼多多初始化值：

```text
bizId   = 1
appId   = 1
bizCode = PDD
```

### 14.1 为什么业务表需要同时保留三个字段

- `bizId` 使用数字自增主键，所有交易表使用 8 字节外键，联合索引更小，Join 和范围查询成本更低。
- `appId` 是客户端协议的一部分。它可能因为产品分包或客户端版本体系与内部数据库主键规划不同，所以不能直接拿来当所有表的外键。
- `bizCode` 是稳定、可读的业务编码，适合日志标签、配置文件、监控维度和业务策略工厂，例如 `PDD`、`ZHIBO_AI`、`ZHIBO_LIVE`。它比数据库自增 ID 更容易排查问题。

### 14.2 字段存储原则

只有 `pdk_business` 同时保存 `bizId + appId + bizCode`。其他业务表原则上只保存 `biz_id`，不应在每张表重复保存 `app_id` 和 `biz_code`：

- 重复保存会产生一行里 `biz_id=1`、`app_id=2`、`biz_code=PDD` 的矛盾组合；
- 修改业务名称或展示信息会产生大量无意义更新；
- 字符串 `bizCode` 放入所有联合索引会显著放大索引体积；
- 服务端只需在请求入口根据唯一索引解析一次 `appId -> BusinessContext`，后续全链路使用 `bizId`。

例外是对外导出的不可变财务凭证或消息事件，可以同时写入 `bizCode` 快照，防止离线系统必须回查主库。但快照只用于展示，不能作为关联和权限依据。

客户端可以上传 `appId`，不建议上传 `bizId` 或以 `bizCode` 作为权威参数。即使客户端同时上传，也必须以服务端通过 `appId` 查出的业务记录为准。

### 14.3 appId 是否能代表客户端真实性

不能。`appId` 是公开路由标识，任何人都能复制。它只能回答“本次请求希望访问哪个业务”，不能证明请求来自正版客户端。用户认证仍依赖密码、Sa-Token、设备 UUID 和服务端用户业务归属。桌面客户端内也不应放置长期 `appSecret` 并假设它无法被提取。

## 15. 索引总体原则

增加 `biz_id` 后，不是给每张表单独建立一个 `INDEX(biz_id)` 就够了。实际查询通常是“某业务下按状态/用户/时间分页”，应把 `biz_id` 放在联合索引最左侧，再按等值条件、范围条件和排序字段排列。

基本顺序：

```text
biz_id → 等值过滤列 → 时间/范围列 → 排序辅助列
```

注意事项：

- `status`、`role_code` 等低基数字段不适合单独建索引，但适合放在 `biz_id` 和高选择性字段之后。
- `LIKE '%关键词%'` 无法有效使用普通 B-Tree 索引。管理端模糊搜索不应为了“看起来完整”建立大量无效索引；数据量大后应改前缀搜索或全文索引。
- 已经由复合索引左前缀覆盖的单列索引通常可以删除，避免写放大。
- 所有后台分页必须带稳定排序，例如 `created_at DESC, id DESC`，相应索引最后附带 `id`，避免同一时间值导致翻页重复或遗漏。
- 不要把业务描述、套餐描述等长文本放进索引。
- 外键列不一定需要独立索引；如果已经是联合索引首列或前缀，应避免重复。

## 16. 各表推荐索引

以下索引名称和字段顺序按当前项目真实查询路径设计。最终上线前仍需用生产数据量执行 `EXPLAIN ANALYZE` 验证。

### 16.1 `pdk_business`

```sql
PRIMARY KEY (id),
UNIQUE KEY uk_business_app_id (app_id),
UNIQUE KEY uk_business_code (biz_code),
KEY idx_business_status_mode (status, registration_mode)
```

`appId -> BusinessContext` 是每个请求入口的高频查询，必须唯一索引并在本地缓存。`bizCode` 必须大小写归一化为大写，并使用区分规则明确的字符集/排序规则，避免 `pdd` 和 `PDD` 是否相同依赖数据库默认行为。

### 16.2 `pdk_user`

删除现有 `UNIQUE(phone)`，改为：

```sql
UNIQUE KEY uk_user_biz_phone (biz_id, phone),
KEY idx_user_biz_status_created (biz_id, status, created_at DESC, id DESC),
KEY idx_user_biz_device (biz_id, device_id)
```

登录、注册查重和修改密码都走 `uk_user_biz_phone`。用户管理页按业务和状态分页走 `idx_user_biz_status_created`。

`device_id` 不建议唯一：设备更换、管理员解绑和历史脏数据可能导致迁移失败。如果产品明确要求一台电脑在同一业务只能绑定一个账号，可在清洗数据后把它升级为业务级唯一约束；否则只做查询索引。

用户页面展示业务名称和描述时，应使用一条 `pdk_user JOIN pdk_business` 的分页查询返回 `UserListView`，不要继续在 Java 中逐用户查询业务，以免形成 N+1 查询。

### 16.3 `pdk_sms_verification`

替换现有 `(phone, purpose, created_at)`：

```sql
KEY idx_sms_biz_phone_purpose_time
    (biz_id, phone, purpose, created_at DESC),
KEY idx_sms_biz_phone_pending
    (biz_id, phone, purpose, status, created_at DESC)
```

发送频控查询使用第一个索引；验证并消费最新 `PENDING` 验证码使用第二个。否则业务 A 刚发过验证码会错误限制业务 B 的同手机号。

### 16.4 `pdk_invitation_code` 与 `pdk_user_referral`

```sql
-- pdk_invitation_code
UNIQUE KEY uk_invitation_biz_code (biz_id, code),
UNIQUE KEY uk_invitation_biz_owner (biz_id, owner_user_id),
KEY idx_invitation_biz_status (biz_id, status);

-- pdk_user_referral
UNIQUE KEY uk_referral_biz_user (biz_id, user_id),
KEY idx_referral_biz_partner_time (biz_id, partner_user_id, created_at DESC)
```

邀请码解析必须同时使用 `bizId + code`。邀请码可以选择保持全平台唯一以方便人工沟通，但业务校验仍不能省略。若保持全局唯一，可保留 `UNIQUE(code)`，同时再加业务查询索引。

邀请人、被邀请人和邀请码必须属于相同 `bizId`。跨业务邀请码注册应返回业务不匹配，而不是静默建立关系。

### 16.5 `pdk_package_plan`

```sql
KEY idx_plan_biz_owner_status_time
    (biz_id, owner_user_id, status, created_at DESC, id DESC),
KEY idx_plan_biz_status_time
    (biz_id, status, created_at DESC, id DESC),
KEY idx_plan_biz_name_owner
    (biz_id, name, owner_user_id)
```

套餐版本号应在同一 `bizId + owner + name` 内递增。当前代码先 `COUNT` 再 `+1`，并发创建可能得到重复版本号。建议建立真正的唯一约束并在事务中生成版本：

```text
UNIQUE(biz_id, owner_scope, name, version_no)
```

由于 MySQL 唯一索引允许多个 `NULL`，平台套餐的 `owner_user_id=NULL` 会绕过唯一性。可以新增生成列：

```sql
owner_scope BIGINT GENERATED ALWAYS AS (IFNULL(owner_user_id, 0)) STORED
```

再建立：

```sql
UNIQUE KEY uk_plan_version
    (biz_id, owner_scope, name, version_no)
```

### 16.6 `pdk_card_key`

建议继续保持 `card_key` 全平台唯一，减少客服输入卡密时的歧义，同时增加：

```sql
KEY idx_card_biz_status_time
    (biz_id, status, created_at DESC, id DESC),
KEY idx_card_biz_generator_status_time
    (biz_id, generated_by_admin, status, created_at DESC, id DESC),
KEY idx_card_biz_activated_user_status
    (biz_id, activated_user_id, status)
```

建议新增 `activated_user_id`，不要再以 `activated_by_phone` 作为关联键。同一手机号可存在于多个业务后，仅按手机号查询卡密会发生串业务；手机号字段保留为财务/客服展示快照。

卡密查询仍可通过全局 `UNIQUE(card_key)` 快速锁行，但锁行后必须校验 `card.bizId == request.bizId`。

### 16.7 `pdk_token_pool`

现有调度查询条件是业务、未废弃、健康、容量未满，并按风险和调用量排序，建议：

```sql
UNIQUE KEY uk_token_pool_uuid (uuid),
KEY idx_token_biz_sched
    (biz_id, is_discarded, health_status, risk_score, daily_calls_count, id),
KEY idx_token_biz_status_created
    (biz_id, health_status, created_at DESC, id DESC)
```

`daily_calls_count < daily_max_capacity` 是列与列比较，B-Tree 无法完全优化这部分，但前面的业务和状态条件仍能大量缩小扫描范围。小号 UUID 建议继续全局唯一；如果供应商只保证业务内唯一，则改为 `UNIQUE(biz_id, uuid)`，两种策略必须在建表前确定。

所有 `FOR UPDATE` 调度 SQL 必须包含 `biz_id = ?`，否则即使表上有索引也不会形成业务隔离。

### 16.8 `pdk_account_assignment`

```sql
KEY idx_assignment_biz_user_active
    (biz_id, user_id, status, expire_at, used_calls, slot_index),
KEY idx_assignment_biz_token_status
    (biz_id, token_id, status),
KEY idx_assignment_biz_card
    (biz_id, card_key_id)
```

第一个索引服务用户领取下一个可用槽位以及套餐详情；第二个服务检查小号是否已被 ACTIVE assignment 占用。

MySQL 没有通用的部分唯一索引。为了从数据库层保证一个小号同一时刻只能有一条 ACTIVE 分配，可增加生成列：

```sql
active_token_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN status = 'ACTIVE' THEN token_id ELSE NULL END) STORED,
UNIQUE KEY uk_assignment_active_token (biz_id, active_token_id)
```

因为唯一索引允许多个 `NULL`，历史 `RELEASED/REPLACED` 记录可以保留，而 ACTIVE 记录不能重复占用同一小号。这比只依赖 `NOT EXISTS ... FOR UPDATE` 更能防止并发超分配。

### 16.9 `pdk_dispatch_log`

建议新增 `user_id`，手机号和账号别名只保留为展示快照：

```sql
UNIQUE KEY uk_dispatch_req_uuid (req_uuid),
KEY idx_dispatch_biz_user_time
    (biz_id, user_id, created_at DESC, id DESC),
KEY idx_dispatch_biz_user_status_time
    (biz_id, user_id, exec_status, created_at DESC, id DESC),
KEY idx_dispatch_biz_account_status_time
    (biz_id, real_account_id, exec_status, created_at DESC)
```

当前 `(user_phone, created_at)` 必须升级为业务和用户 ID 维度，否则同手机号跨业务的成功/失败次数会混合。`req_uuid` 可以继续全局唯一，便于全局幂等和排障。

### 16.10 `pdk_financial_income`

建议增加 `user_id` 和 `partner_user_id`，避免仅靠手机号和操作员字符串关联：

```sql
UNIQUE KEY uk_income_order_no (income_order_no),
KEY idx_income_biz_time (biz_id, created_at DESC, id DESC),
KEY idx_income_biz_type_time (biz_id, order_type, created_at DESC, id DESC),
KEY idx_income_biz_user_time (biz_id, user_id, created_at DESC),
KEY idx_income_biz_partner_time (biz_id, partner_user_id, created_at DESC)
```

代理查看自己的销售记录应按 `partner_user_id` 过滤，不应长期依赖 `audit_admin=手机号`。用户名字符串会因改名、账号迁移或不同类型管理员而不稳定。

### 16.11 `pdk_company_expense`

```sql
KEY idx_expense_biz_time (biz_id, purchased_at DESC, id DESC),
KEY idx_expense_category_time (category, purchased_at DESC, id DESC)
```

`biz_id=NULL` 表示平台公共成本。如果一笔采购需要分摊多个业务，不应复制支出记录，后续可增加 `pdk_expense_allocation(expense_id, biz_id, allocation_amount)`。

### 16.12 `pdk_admin_audit_log`

```sql
KEY idx_audit_biz_admin_time
    (biz_id, admin_name, created_at DESC, id DESC),
KEY idx_audit_biz_target_time
    (biz_id, target_type, target_id, created_at DESC, id DESC)
```

业务管理本身属于平台级操作，可允许 `biz_id=NULL`；对用户、套餐、卡密、小号的操作必须写入目标业务 ID。

### 16.13 `pdk_system_config`

业务名称、描述、注册模式和试用参数已有明确业务语义，优先存放在 `pdk_business`，不要同时在系统配置表保存一份。

若未来确实需要大量业务级动态配置，再将唯一键从 `config_key` 调整为：

```sql
UNIQUE KEY uk_config_scope_key (scope_type, scope_id, config_key),
KEY idx_config_scope_group (scope_type, scope_id, config_group)
```

其中平台配置为 `scope_type=PLATFORM, scope_id=0`，业务配置为 `scope_type=BUSINESS, scope_id=bizId`。读取顺序为业务覆盖值优先、平台默认值兜底。

## 17. 业务关联与一致性约束

### 17.1 业务关联主链

```text
pdk_business
  ├─ pdk_user ─ pdk_user_credential
  │    ├─ pdk_user_referral / pdk_invitation_code
  │    ├─ pdk_account_assignment ─ pdk_token_pool
  │    └─ pdk_dispatch_log
  ├─ pdk_package_plan ─ pdk_card_key
  │                         ├─ pdk_account_assignment
  │                         └─ pdk_financial_income
  ├─ pdk_company_expense
  └─ pdk_admin_audit_log
```

每一次跨表写操作都必须先得到一个不可变的 `BusinessContext`，然后在同一事务内验证所有参与对象的 `bizId` 相同。

### 17.2 注册

必须满足：

```text
business.appId == request.appId
business.status == ACTIVE
business.registrationMode == SELF_SERVICE
sms.bizId == business.bizId
invitation.bizId == business.bizId（填写邀请码时）
UNIQUE(user.bizId, user.phone)
```

试用参数取业务配置，不再读取一套全局 `pdk.trial.*` 作为所有业务的最终值；全局参数最多作为新业务默认值。

### 17.3 管理员建号

`AdminCreateUserDTO` 增加 `bizId`。服务端校验管理员对该业务有管理权限，创建 `source=ADMIN_CREATED` 用户，不发短信、不自动试用。初始密码与登录 DTO 统一为 8～64 位。

如果管理员传入设备 UUID，直接预绑定；不传则首次登录用条件更新完成绑定：

```sql
UPDATE pdk_user
SET device_id = :deviceId
WHERE id = :userId AND biz_id = :bizId AND device_id IS NULL;
```

更新失败后重新读取，只有设备一致才允许继续，避免两个设备同时首次登录时都认为自己绑定成功。

### 17.4 登录与会话

登录查询只能是：

```sql
SELECT ... FROM pdk_user
WHERE biz_id = :bizId AND phone = :phone;
```

Sa-Token 登录 ID 继续使用全局 `user.id`。会话中或服务端缓存中记录 `bizId`；每次请求校验 Header appId 解析结果、会话用户和数据库用户三者一致。

业务停用后，不能只阻止新登录；`DeviceSecurityInterceptor` 也必须在每个已登录请求中检查业务状态，从而让历史会话立即停止业务调用。

### 17.5 套餐和制卡

创建套餐时：

```text
plan.bizId = 当前管理业务
partner.bizId = plan.bizId
```

制卡时：

```text
card.bizId = plan.bizId = 当前管理业务
```

套餐是不可变版本，业务归属也不可修改。不能通过更新套餐业务来“搬迁”历史卡密；需要在目标业务创建新套餐版本。

### 17.6 卡密激活

卡密激活事务至少验证：

```text
request.appId -> request.bizId
user.bizId == request.bizId
card.bizId == request.bizId
plan.bizId == request.bizId
即将分配的每个 token.bizId == request.bizId
assignment.bizId == request.bizId
```

任何一个不一致都必须整体回滚。卡密全局唯一并不代表可以省略业务一致性校验。

续费时还要验证原卡密、用户和新套餐业务一致，禁止拿业务 B 套餐给业务 A 原卡续费。

### 17.7 小号导入和调度

小号录入、批量导入、恢复和废弃操作都必须携带目标 `bizId`。已经存在 ACTIVE assignment 的小号禁止修改业务；需要迁移时必须先释放全部 assignment，再通过受审计的专门操作迁移。

调度入口不要再按手机号重新查询用户，优先使用拦截器放入请求的 `User` 或 `userId`，避免遗漏 biz 条件。分配和故障替换查询都必须限定同一业务。

### 17.8 消费、销售和财务

消费流水的业务从已验证的租约/assignment 继承，不能接受客户端上报 bizId 后直接写入。销售流水的业务从卡密继承，续费流水同样从原卡密继承。

财务汇总必须明确查询范围：

- 超级管理员：全业务或选定业务；
- 代理：仅自己的 `bizId + partnerUserId`；
- 公共支出：单独展示或按照分摊表计算，不能随意计入某一业务利润。

### 17.9 邀请码与代理

现有 PARTNER 身份属于 `pdk_user`，因此天然属于一个业务。邀请码、代理创建的套餐、卡密和销售记录都继承该用户的 `bizId`。

如果未来一个代理需要经营多个业务，不建议复制或篡改用户业务归属，应新增：

```text
pdk_partner_business_scope(partner_user_id, biz_id, status)
```

并把权限校验升级为显式业务授权。目前需求下先保持“一个 PARTNER 用户属于一个业务”，改动最小且边界清晰。

## 18. 数据库外键策略

现有项目基本依赖应用层关联，没有声明外键。多业务后有两种可选强度：

### 方案 A：应用层校验 + 关键唯一约束（推荐首期）

- 所有业务表 `biz_id NOT NULL`；
- `biz_id` 引用 `pdk_business.id` 的有效性由服务统一校验；
- 用复合唯一索引、ACTIVE assignment 生成列唯一索引防止最危险的重复数据；
- 所有跨表写入在事务中比较 bizId；
- 上线改动小，避免历史脏数据导致一次性无法建立大量外键。

### 方案 B：增加复合外键（稳定后增强）

例如让卡密从数据库层保证与套餐同业务，需要被引用表先有：

```sql
UNIQUE KEY uk_plan_id_biz (id, biz_id)
```

然后建立：

```sql
FOREIGN KEY (package_id, biz_id)
REFERENCES pdk_package_plan(id, biz_id)
```

assignment 也可以分别通过 `(user_id,biz_id)`、`(token_id,biz_id)`、`(card_key_id,biz_id)` 建立复合外键，从数据库层阻止跨业务关联。

复合外键一致性更强，但会增加写入顺序、历史数据清洗和运维复杂度。建议先完成应用隔离和数据清洗，观察一个版本后再添加；不要在未清洗的生产库直接启用。

所有历史/流水类外键避免 `ON DELETE CASCADE`。用户、卡密、套餐和小号都应逻辑停用或废弃，不能因为删除主记录连带删除财务及审计证据。

## 19. 索引迁移与验证步骤

索引调整应按以下顺序执行，避免先删除旧唯一约束后写入重复数据：

1. 创建 `pdk_business` 和拼多多 `bizId=1/appId=1`。
2. 给各表增加可空 `biz_id`。
3. 历史数据回填 `biz_id=1`。
4. 用核对 SQL 检查空业务、重复 `(biz_id,phone)`、跨业务关联和重复 ACTIVE assignment。
5. 部署所有读写都带 bizId 的兼容代码。
6. 建立新的业务联合索引和唯一约束。
7. 删除被替代的旧索引，例如用户 `UNIQUE(phone)` 和短信旧联合索引。
8. 将必填业务列改为 `NOT NULL`。
9. 通过慢查询日志和 `EXPLAIN ANALYZE` 检查索引命中情况。
10. 最后关闭旧客户端 appId 缺省兼容。

重点核对 SQL 应覆盖：

```sql
-- 是否还有未归属业务的数据
SELECT COUNT(*) FROM pdk_user WHERE biz_id IS NULL;

-- 业务内手机号是否重复
SELECT biz_id, phone, COUNT(*)
FROM pdk_user
GROUP BY biz_id, phone
HAVING COUNT(*) > 1;

-- assignment 是否跨业务关联
SELECT a.id
FROM pdk_account_assignment a
JOIN pdk_user u ON u.id = a.user_id
JOIN pdk_token_pool t ON t.id = a.token_id
WHERE a.biz_id <> u.biz_id OR a.biz_id <> t.biz_id;

-- 卡密与套餐是否跨业务
SELECT c.id
FROM pdk_card_key c
JOIN pdk_package_plan p ON p.id = c.package_id
WHERE c.biz_id <> p.biz_id;
```

`schema-mysql.sql` 自动初始化机制继续保留，但索引迁移必须记录版本并支持重复启动。每一项 `ALTER/DROP INDEX/CREATE INDEX` 都先查询 `information_schema` 判断当前状态，不能假定数据库一定来自最新版空库。

## 20. `bizCode` 对业务实现的作用

`bizCode` 不应只作为管理页面上的缩写。它适合作为服务端选择业务实现策略的稳定键，但不能在 Controller 和 Service 中散落大量：

```java
if (appId == 1) { ... } else if (appId == 2) { ... }
```

建议建立业务策略接口：

```java
public interface BusinessHandler {
    String bizCode();
    void validateAction(String actionType, Map<String, Object> payload);
    String buildEncryptedCredential(ResourceAccount account, LeaseContext lease);
    FailureDecision classifyFailure(String clientStatus, String errorCode);
}
```

以及注册表：

```text
BusinessHandlerRegistry
  PDD         -> PddBusinessHandler（完整复用当前拼多多逻辑）
  ZHIBO_AI    -> ZhiboAiBusinessHandler
  ZHIBO_LIVE  -> ZhiboLiveBusinessHandler
```

请求入口只负责 `appId -> BusinessContext`；策略注册表使用服务端查出的 `bizCode` 选择实现。这样新增业务不会修改登录、套餐、卡密、扣次和权限等公共主链，只扩展业务特有动作与资源协议。

### 20.1 哪些能力应保持公共

- appId/bizId 解析与权限隔离；
- 手机号、密码、短信或管理员建号；
- 设备 UUID 绑定；
- 套餐、卡密、期限和次数；
- 账号资源独占分配、租约和幂等；
- 消费、销售、财务与审计；
- 数据加密信封和统一返回码。

### 20.2 哪些能力允许按 bizCode 扩展

- 客户端允许的 `actionType`；
- 小号/资源凭证的数据结构；
- 实际业务请求参数校验；
- 账号失效、封禁、网络错误的分类规则；
- 下发给客户端的加密 payload 内容；
- 资源健康检查和替换规则；
- 客户端功能开关与业务说明。

### 20.3 当前拼多多命名的泛化

当前代码和表中存在 `PdkDispatchLog.realPddAccountId`、`pdk_token_pool.token_val` 等拼多多专用命名。若业务 A/B 仍然都是相同 Token 调度模型，可以首期保留字段以降低改动，但 Java 新接口应使用通用命名：

```text
ResourceAccount
credentialPayload
realAccountId
BusinessHandler
```

数据库可以分阶段将：

```text
real_pdd_account_id -> real_account_id
token_val           -> credential_payload
```

其中 `credential_payload` 应保存服务端加密后的 JSON，具体字段由业务 Handler 解释。不能把不同业务的用户名、Cookie、Token 等凭证结构继续硬塞进一个仅叫 `token_val` 的明文字段。

为了兼容现有拼多多方案，第一阶段可以同时保留旧列和通用访问层，由 `PddBusinessHandler` 读取旧 `token_val`；完成数据迁移后再删除旧命名。无论是否改名，资源表都必须先增加 `biz_id`。

## 21. 业务动作和客户端能力关联

不同 appId 对应不同客户端，客户端能执行的动作很可能不同。不能继续只靠全局 DTO 枚举允许所有动作。

建议增加业务动作配置表：

```sql
CREATE TABLE pdk_business_action (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    biz_id BIGINT NOT NULL,
    action_code VARCHAR(64) NOT NULL,
    action_name VARCHAR(64) NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    deduct_calls INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    UNIQUE KEY uk_biz_action (biz_id, action_code),
    KEY idx_biz_action_status (biz_id, status)
);
```

领取资源时校验 `actionType` 属于当前 `bizId`。租约记录 bizId 和 actionCode，结果上报从租约继承业务与动作，不信任客户端再次声明。

公开业务信息接口可以返回该 appId 的客户端能力：

```json
{
  "appId": 1,
  "bizCode": "PDD",
  "businessName": "拼多多业务",
  "businessDescription": "现有拼多多采集与分发业务",
  "registrationMode": "SELF_SERVICE",
  "actions": [
    {"code": "GOODS_COLLECT", "name": "商品采集"},
    {"code": "ORDER_PULL", "name": "订单拉取"}
  ]
}
```

生产客户端的 appId 固化在构建配置中；服务端返回的能力用于显示/隐藏功能，而不能把业务 B 的功能入口硬编码进客户端 A。

## 22. 主要现有查询的改造对照

| 当前逻辑 | 风险 | 改造后 |
| --- | --- | --- |
| `User WHERE phone=?` | 同手机号跨业务串号 | `WHERE biz_id=? AND phone=?` |
| `CardKey WHERE activated_by_phone=?` | 查询到其他业务卡密 | 按 `biz_id + activated_user_id` |
| `DispatchLog WHERE user_phone=?` | 消费统计跨业务混合 | 按 `biz_id + user_id` |
| `Device Redis Key(phone)` | 业务 A/B 互踢 | Key 使用 `bizId:userId` |
| `Lease consume(traceId, phone)` | 租约缺少业务所有权 | 校验 `traceId + bizId + userId` |
| `TokenPool healthy LIMIT n` | 分配其他业务资源 | `WHERE biz_id=?` 后再调度 |
| `Package list owner/status` | 代理看到其他业务模板 | 增加授权 bizId 条件 |
| `Card renew -> User by phone` | 原卡续错业务用户 | 卡密保存 userId，按 ID+bizId 查询 |
| `Finance selectList(null)` | 大盘无法按业务核算且全表扫描 | 按 bizId、时间范围聚合并命中索引 |
| `SystemConfig key globally unique` | 业务策略只能全平台一份 | 核心业务字段放业务表，扩展配置使用 scope 唯一键 |

## 23. 删除、停用和业务迁移规则

### 23.1 业务不能物理删除

只要业务下存在用户、卡密、消费或财务记录，业务只能设置为 `DISABLED`。历史查询和财务审计仍需要 `pdk_business` 提供名称和 bizCode。

### 23.2 停用业务的影响

- 拒绝短信注册、自助注册和管理员新增用户；
- 拒绝新登录；
- 拦截已有会话的业务调用和资源领取；
- 禁止制套餐、制卡、续费和导入小号；
- 保留超级管理员只读查询和历史报表；
- 是否允许管理员恢复业务由审计权限控制。

### 23.3 用户不能直接改 bizId

用户业务归属一旦创建不可修改。若确需把用户从 A 迁到 B，应创建目标业务的新用户，并通过专门、可审计的迁移任务处理权益；不能直接 `UPDATE pdk_user SET biz_id=?`，否则凭证、卡密、assignment 和流水会形成跨业务关系。

### 23.4 小号资源迁移

只有 `HEALTHY/EXPIRED` 且不存在 ACTIVE assignment、BUSY 租约的资源允许迁移业务。迁移必须记录原 bizId、目标 bizId、原因和管理员，不允许在普通编辑接口直接修改。

## 24. 缓存、监控和审计维度

业务解析建议使用本地短缓存或 Redis：

```text
pdk:business:app:{appId} -> bizId/bizCode/status/configVersion
```

业务配置修改后主动失效缓存。业务状态校验不能无限期缓存，避免停用后旧配置长期生效。

所有结构化日志、指标和审计事件至少带：

```text
bizId, bizCode, appId, userId, traceId
```

但密码、验证码、卡密全文、Token 和资源 credential 不得写入日志。建议监控：

- 按 appId 的注册、登录成功率；
- `ADMIN_ONLY` 业务被尝试自助注册的次数；
- 缺少 appId 的兼容请求数量；
- appId 与会话业务不匹配次数；
- 跨业务卡密/套餐/资源校验失败次数；
- 各业务资源库存、租约、扣次和故障替换；
- 各业务收入、成本和毛利。

## 25. 是否需要按业务进行目录和类隔离

### 25.1 结论

有必要按业务隔离类和目录，但只隔离“业务差异”，不复制整套平台代码。

推荐结构是：

```text
公共平台内核 + 业务 SPI + 各 bizCode 独立实现目录
```

不推荐为业务 A、业务 B 分别复制一套：

```text
UserControllerA / UserControllerB
LoginServiceA / LoginServiceB
PackageServiceA / PackageServiceB
CardServiceA / CardServiceB
```

用户、登录、权限、设备、套餐、卡密、次数、财务和审计规则大部分相同，复制后会产生重复修复、行为不一致和安全漏洞遗漏。

### 25.2 推荐后端目录

在当前单体 Spring Boot 项目内，第一阶段建议先做 Java Package 隔离：

```text
com.pdk
├─ platform
│  ├─ business
│  │  ├─ BusinessContext.java
│  │  ├─ BusinessContextResolver.java
│  │  ├─ BusinessRegistry.java
│  │  └─ BusinessService.java
│  ├─ auth
│  ├─ user
│  ├─ device
│  ├─ entitlement
│  ├─ packageplan
│  ├─ card
│  ├─ resource
│  ├─ dispatch
│  ├─ finance
│  └─ audit
├─ business
│  ├─ spi
│  │  ├─ BusinessHandler.java
│  │  ├─ BusinessAction.java
│  │  ├─ CredentialCodec.java
│  │  ├─ ResourceHealthChecker.java
│  │  └─ FailureClassifier.java
│  ├─ pdd
│  │  ├─ PddBusinessHandler.java
│  │  ├─ PddActionValidator.java
│  │  ├─ PddCredentialCodec.java
│  │  ├─ PddFailureClassifier.java
│  │  └─ PddResourceHealthChecker.java
│  ├─ zhiboai
│  │  ├─ ZhiboAiBusinessHandler.java
│  │  ├─ ZhiboAiActionValidator.java
│  │  └─ ...
│  └─ zhibolive
│     ├─ ZhiboLiveBusinessHandler.java
│     └─ ...
├─ controller
├─ domain
├─ mapper
├─ security
└─ config
```

现有代码不需要一次性全部移动到 `platform`。为降低风险，可以先新增 `business/spi` 和 `business/pdd`，以后新增业务直接进入独立目录；公共类在后续重构时逐步归位。

### 25.3 公共和业务专属类的判断标准

| 能力 | 归属 | 原因 |
| --- | --- | --- |
| appId 解析、业务状态 | 公共平台 | 所有业务共用入口 |
| 手机号密码登录 | 公共平台 | 认证规则统一 |
| 短信注册/管理员建号策略 | 公共平台读取业务配置 | 流程公共，开关不同 |
| 设备 UUID 绑定 | 公共平台 | 安全规则统一 |
| 套餐、卡密、次数 | 公共平台 | 商业化模型统一 |
| 财务、审计 | 公共平台 | 必须统一核算和追踪 |
| actionType 校验 | 业务目录 | 各业务动作不同 |
| 业务请求参数结构 | 业务目录 | 字段和校验不同 |
| 资源凭证编码/解码 | 业务目录 | Token、Cookie、账号密码可能不同 |
| 账号健康检查 | 业务目录 | 各平台失效规则不同 |
| 错误码到失败类型映射 | 业务目录 | 各平台返回规则不同 |
| 下发给客户端的业务 payload | 业务目录 | 客户端能力不同 |

判断原则是：如果一个规则变化时只影响某个 `bizCode`，它应位于该业务目录；如果变化必须对所有业务同时生效，它应位于公共平台内核。

### 25.4 统一 Controller，不按业务复制 URL

仍然保留统一接口：

```text
/api/v1/client/auth/login
/api/v1/card/activate
/api/v1/dispatch/acquire-token
/api/v1/dispatch/report-result
```

Controller 获取 `BusinessContext` 后调用注册表：

```java
BusinessHandler handler = businessRegistry.require(context.bizCode());
handler.validateAction(dto.getActionType(), dto.getPayload());
```

不建议创建：

```text
/api/v1/pdd/...
/api/v1/zhibo-ai/...
/api/v1/zhibo-live/...
```

否则客户端协议、鉴权拦截器、日志和错误码会逐渐分裂。只有某个业务确实存在完全独立且其他业务永远不会使用的接口时，才允许在对应业务目录中增加专属 Controller，例如：

```text
/api/v1/business/pdd/special-operation
```

专属 Controller 仍必须经过统一 BusinessContext 和权限拦截器。

### 25.5 Handler 注册方式

建议每个 Handler 是 Spring Bean：

```java
@Component
public class PddBusinessHandler implements BusinessHandler {
    @Override
    public String bizCode() {
        return "PDD";
    }
}
```

启动时将所有 Handler 注册成不可变 Map：

```text
Map<bizCode, BusinessHandler>
```

并做启动校验：

- 不能有两个 Handler 声明同一个 bizCode；
- 数据库 ACTIVE 业务必须存在对应 Handler；
- Handler 的 bizCode 必须与数据库业务编码完全一致；
- 找不到 Handler 时业务不能提供调度服务，但管理后台仍可查看历史数据。

不能在数据库里保存任意 Java 类名并通过反射实例化，这会造成安全和发布不可控。数据库只保存稳定 bizCode，代码注册表决定具体实现。

### 25.6 何时从 Package 升级为 Maven 多模块

当前阶段不必立即拆成多个微服务，也不必一开始就建多个仓库。建议先使用目录/Package 隔离；满足以下任一条件后再升级为 Maven 多模块：

- 业务专属代码超过约 30～50 个类；
- 不同业务由不同团队独立发布；
- 某业务依赖专属、体积较大的第三方 SDK；
- 某业务存在依赖版本冲突；
- 需要在某些部署环境完全不打包某个业务；
- 各业务发布节奏明显不同。

推荐多模块结构：

```text
pdk-parent
├─ pdk-platform-core
├─ pdk-business-spi
├─ pdk-business-pdd
├─ pdk-business-zhibo-ai
├─ pdk-business-zhibo-live
└─ pdk-application
```

依赖方向必须单向：

```text
business-pdd ─┐
business-zhibo-ai   ├─> business-spi -> platform-core
business-zhibo-live ┘

application -> platform-core + 所有需要启用的 business 模块
```

`platform-core` 绝不能反向依赖 `business-pdd`，否则公共内核仍然被具体业务绑死。

### 25.7 业务专属数据库表和迁移目录

公共表继续使用 `pdk_user`、`pdk_package_plan`、`pdk_card_key`、`pdk_account_assignment` 等，通过 bizId 隔离。

只有某业务出现无法放入通用模型的专属数据时，才增加带业务前缀的扩展表，例如：

```text
pdk_pdd_shop_extension
pdk_zhibo_ai_account_extension
```

扩展表至少包含：

```text
id, biz_id, user_id/resource_id, created_at, updated_at
```

不建议为每个业务复制 `pdd_user`、`zhibo_ai_user`、`pdd_card`、`zhibo_ai_card`。这会破坏统一用户中心和财务中心。

迁移脚本可以按目录组织后再由主初始化机制统一执行：

```text
db/migration/platform/
db/migration/business/pdd/
db/migration/business/zhibo-ai/
db/migration/business/zhibo-live/
```

但 Spring Boot 启动自动初始化和版本记录仍由平台统一管理，不能让每个 Handler 在运行时自行执行任意 DDL。

### 25.8 前端和客户端目录

管理后台保持一个项目，新增通用业务选择器；业务专属管理组件可按目录隔离：

```text
admin-vue3/src
├─ views/business/             # 通用业务管理
├─ business/pdd/               # 拼多多专属管理组件
├─ business/zhibo-ai/
└─ business/zhibo-live/
```

PyQt/SDK 推荐“公共客户端内核 + 不同 appId 构建配置”：

```text
client-core/
client-apps/
├─ pdd/app-config.json         # appId=1, bizCode=PDD
├─ zhibo-ai/app-config.json     # appId=2, bizCode=ZHIBO_AI
└─ zhibo-live/app-config.json   # appId=3, bizCode=ZHIBO_LIVE
```

若客户端 A/B 界面和功能差异不大，使用同一套代码按配置构建即可；如果界面、工作流和依赖完全不同，再创建独立客户端入口，但 HTTP、加密、登录和设备 SDK 仍复用公共库。

### 25.9 测试目录

公共契约测试与业务实现测试分开：

```text
src/test/java/com/pdk/platform/       # 登录、卡密、隔离、财务等公共测试
src/test/java/com/pdk/business/pdd/   # PDD action、错误映射、凭证测试
src/test/java/com/pdk/business/zhiboai/
src/test/java/com/pdk/business/zhibolive/
```

每个新 Handler 必须通过同一套 `BusinessHandlerContractTest`，至少验证 bizCode 唯一、动作校验、凭证不串业务、错误映射和资源替换。

### 25.10 是否拆成独立微服务

当前不建议仅因为增加 appId 就拆微服务。当前用户、套餐、卡密、小号分配、扣次和财务存在强事务关联，拆服务会引入分布式事务、消息一致性和运维复杂度。

只有当业务之间需要独立扩缩容、独立合规隔离、独立数据库或完全不同发布周期时，再考虑：

```text
统一身份/商业化平台 + 各业务执行服务
```

即使未来拆服务，bizId、统一用户身份、套餐、卡密、财务和审计仍应由平台侧保持权威，业务执行服务只负责业务特有操作。

## 26. 三个业务的后台开关与按部署启用

### 26.1 必须区分两种“启用”

业务可用性由两个独立维度决定：

1. **后台配置开关**：`pdk_business.status`，由超级管理员在业务管理页面操作；
2. **部署能力开关**：当前运行包是否包含 Handler，以及环境配置是否允许加载该 bizCode。

最终状态计算：

```text
effectiveEnabled
  = databaseStatus == ACTIVE
  AND bizCode in deploymentEnabledCodes
  AND handlerRegistered == true
  AND handlerHealth == UP
```

不能只依赖数据库开关。否则 PDD-only 部署读取到 `ZHIBO_AI=ACTIVE` 后，会对外宣称业务可用，但运行包里没有对应实现。

也不能只依赖部署配置。否则每次临时关闭业务都需要重启或重新发布，管理后台开关失去意义。

### 26.2 建议环境配置

在 `application.yml` 增加：

```yaml
pdk:
  business:
    # 默认只启用现有 PDD，避免升级后误开放两个新业务
    enabled-codes: ${PDK_ENABLED_BIZ_CODES:PDD}
    legacy-default-app-id: ${PDK_LEGACY_DEFAULT_APP_ID:1}
    allow-legacy-missing-app-id: ${PDK_ALLOW_MISSING_APP_ID:true}
```

部署示例：

```text
# 只部署现有拼多多业务
PDK_ENABLED_BIZ_CODES=PDD

# 只部署两个直播业务
PDK_ENABLED_BIZ_CODES=ZHIBO_AI,ZHIBO_LIVE

# 部署全部业务
PDK_ENABLED_BIZ_CODES=PDD,ZHIBO_AI,ZHIBO_LIVE
```

配置值统一使用大写规范化后的 bizCode。未知编码应让启动失败，而不是悄悄忽略拼写错误。

### 26.3 模块是否物理打包

支持两种部署等级：

#### 单包配置裁剪

运行包包含三个 Handler，但通过 `PDK_ENABLED_BIZ_CODES` 选择本环境开放哪些业务。适合当前阶段，构建和发布最简单。

#### Maven 模块物理裁剪

业务模块独立后，通过构建 Profile 决定是否打包：

```text
-Pbiz-pdd
-Pbiz-zhibo-ai
-Pbiz-zhibo-live
-Pbiz-all
```

即使模块被打包，也仍需环境 allowlist；“已打包”代表具备能力，“环境允许”代表本次部署决定使用。

推荐先采用单包配置裁剪，等直播业务依赖或发布节奏明显不同后再做物理裁剪。

### 26.4 三业务初始化种子

`schema-mysql.sql` 应幂等初始化三条业务，但只默认启用 PDD：

```sql
INSERT INTO pdk_business
    (id, app_id, biz_code, biz_name, description, registration_mode,
     trial_enabled, status)
VALUES
    (1, 1, 'PDD', '拼多多业务', '现有拼多多采集与分发业务',
     'SELF_SERVICE', 1, 'ACTIVE'),
    (2, 2, 'ZHIBO_AI', 'zhibo-ai', '直播 AI 业务',
     'ADMIN_ONLY', 0, 'DISABLED'),
    (3, 3, 'ZHIBO_LIVE', 'zhibo-live', '直播 Live 业务',
     'ADMIN_ONLY', 0, 'DISABLED')
ON DUPLICATE KEY UPDATE
    biz_name = VALUES(biz_name),
    description = VALUES(description);
```

幂等脚本不能在每次启动时覆盖管理员已经设置的 `status`、`registration_mode` 和试用参数。因此 `ON DUPLICATE KEY UPDATE` 只能更新确定属于程序元数据的字段，不能把开关强行恢复成种子默认值。

### 26.5 管理后台业务页面状态模型

业务接口返回：

```json
{
  "bizId": 2,
  "appId": 2,
  "bizCode": "ZHIBO_AI",
  "businessName": "zhibo-ai",
  "configuredStatus": "ACTIVE",
  "deploymentEnabled": true,
  "handlerRegistered": true,
  "handlerHealth": "UP",
  "effectiveStatus": "AVAILABLE",
  "unavailableReason": null
}
```

管理页面推荐展示：

| 状态 | 含义 | 后台操作 |
| --- | --- | --- |
| `AVAILABLE` | 后台已启用且当前部署支持 | 可以关闭 |
| `DISABLED_BY_ADMIN` | 管理员关闭 | 部署支持时可以开启 |
| `NOT_IN_DEPLOYMENT` | 当前环境 allowlist 未包含 | 不能开启，提示修改部署配置 |
| `HANDLER_MISSING` | 配置允许但模块未打包/Bean 未注册 | 不能开启，属于部署错误 |
| `HANDLER_UNHEALTHY` | Handler 初始化或依赖检查失败 | 不能开启，显示健康错误 |
| `CONFIG_ERROR` | 数据库业务和代码配置不一致 | 不能开启，要求运维修复 |

后台开关接口建议为：

```http
PUT /api/v1/admin/business/{bizId}/enable
PUT /api/v1/admin/business/{bizId}/disable
GET /api/v1/admin/business/list
GET /api/v1/admin/business/{bizId}/runtime-status
```

启用前必须校验：

- 当前部署 allowlist 包含 bizCode；
- Handler 已注册且健康；
- 注册模式和必要配置完整；
- 业务所需数据库扩展表已完成迁移；
- 必需的资源或第三方配置存在。

任何一项失败都不得把数据库状态改为 ACTIVE。关闭业务属于可逆操作，但必须记录管理员、bizId、修改前后状态和原因。

### 26.6 开关的业务影响

管理员关闭业务后立即：

- 禁止短信注册和自助注册；
- 禁止管理员新增该业务用户；
- 禁止新登录；
- 已登录用户的下一次请求被拦截；
- 禁止激活卡密、续费、制卡、创建套餐和导入资源；
- 禁止领取新租约和上报新的业务结果；
- 保留历史用户、卡密、消费、财务和审计查询；
- 已产生的财务和流水绝不删除。

关闭时正在执行的请求无法绝对瞬时终止。业务状态缓存应主动失效，并设置较短 TTL；关键写接口在事务提交前可再次校验业务状态。

资源租约关闭策略建议：停止发放新租约；对关闭前已经发放的租约，允许在短租约有效期内上报结果并完成幂等收尾，避免资源永久保持 BUSY。上报完成后不再续发。

### 26.7 后台开启与部署支持的关系

管理后台能管理所有数据库业务，但不能凭后台开关“创造”当前部署中不存在的代码能力：

```text
后台开关 = 运营许可
部署 allowlist = 环境许可
Handler 模块 = 代码能力
```

三者缺一不可。当前部署未支持的业务仍显示在业务页面，以便查看配置和历史数据，但启用按钮置灰。

### 26.8 多节点部署限制

同一个负载均衡服务组内的节点应使用完全相同的业务模块和 `PDK_ENABLED_BIZ_CODES`。如果一半节点支持 ZHIBO_AI、另一半不支持，客户端会随机成功或失败。

若确实需要不同节点组只部署部分业务，应选择一种明确路由方式：

- 每个客户端使用不同 API 域名；或
- 所有请求（包括登录、注册）同时携带 `X-PDK-App-ID`，由网关按 appId 路由到对应节点组；或
- 业务执行服务独立，统一平台根据 bizCode 内部路由。

即使请求体也有 appId，仍建议所有请求统一附带 `X-PDK-App-ID`，方便网关在不解析 JSON/加密信封的情况下路由；服务端必须校验 Header 与解密后的请求体 appId 一致。

### 26.9 健康检查与启动校验

建议新增业务健康端点或集成到 Actuator：

```text
/actuator/health/businesses
```

返回当前部署的三个维度：allowlist、Handler 注册、业务依赖健康。启动时执行：

1. allowlist 每个 bizCode 必须存在于代码 Handler 注册表；
2. 数据库每个 ACTIVE 业务必须在当前部署中可用；
3. 同一个 bizCode 不能有两个 Handler；
4. appId 和 bizCode 不能重复；
5. 当前部署支持的业务扩展表和必要配置必须存在。

对于“数据库 ACTIVE 但当前部署不支持”的情况，生产环境建议启动失败，防止流量进入不完整节点；管理/迁移工具环境可以通过只读模式显式放宽。

### 26.10 业务专属定时任务

公共清理任务（过期租约释放、历史状态收尾、审计保留）可以处理所有业务数据。业务专属任务必须按 Handler 和部署开关条件加载：

```text
PddResourceHealthJob          -> 仅 PDD
ZhiboAiResourceHealthJob     -> 仅 ZHIBO_AI
ZhiboLiveResourceHealthJob   -> 仅 ZHIBO_LIVE
```

管理员关闭业务后，业务专属的新任务停止执行，但必要的资源释放和一致性清理仍由公共任务完成。

### 26.11 三个客户端构建配置

```json
// pdd/app-config.json
{"appId": 1, "bizCode": "PDD", "displayName": "拼多多业务"}

// zhibo-ai/app-config.json
{"appId": 2, "bizCode": "ZHIBO_AI", "displayName": "zhibo-ai"}

// zhibo-live/app-config.json
{"appId": 3, "bizCode": "ZHIBO_LIVE", "displayName": "zhibo-live"}
```

生产客户端只信任构建时固定的 appId，不让最终用户切换；调试客户端可以提供业务选择器。客户端启动时读取公开业务状态：未部署显示“当前服务未部署此业务”，管理员关闭显示“业务维护中”，不能统一显示成用户名或密码错误。

### 26.12 部署组合验收

除全链路业务隔离测试外，还要覆盖：

1. PDD-only 部署只能使用 appId=1，后台可看到另外两个业务但无法启用；
2. ZHIBO-only 部署支持 appId=2/3，appId=1 返回明确的未部署错误；
3. 全业务部署可分别启停三个业务，彼此不受影响；
4. 后台关闭 ZHIBO_AI 不影响 ZHIBO_LIVE；
5. 数据库 ACTIVE 但 Handler 缺失时生产实例启动失败；
6. Handler 已打包但不在 allowlist 时不加载专属任务、不接收业务流量；
7. 业务重新开启后历史用户和权益保持不变；
8. 业务关闭前的短租约可以安全收尾但不能继续领取；
9. 三个客户端显示各自的业务名称、描述和有效状态；
10. schema 自动初始化不会在重启时覆盖管理员设置的业务开关。
