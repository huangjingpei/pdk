# 系统分析与当前实现

## 一、模块边界

管理后台只管理平台侧资产和风控任务：卡密、财务、公共小号资源、客户端用户与电脑绑定。客户端负责最终业务动作：登录、激活、领取短效小号资源、执行采集/查询、上报是否成功以及查询自身使用次数。客户端永远不能直接读取公共资源池列表或长期明文凭证。

一次完整调用链为：

1. 客户端领取试用或激活卡密。
2. 客户端用手机号和设备指纹登录，服务端签发独立的 `client` 会话。
3. 客户端领取 5 分钟资源租约，服务端只下发 AES-GCM 加密载荷。
4. 客户端解密并执行实际业务，然后上报 `SUCCESS` 或失败类型。
5. 服务端只在 `SUCCESS` 时扣 1 次；账号封禁时扣 0 次并拉黑底层资源；所有结果写入 `pdk_dispatch_log`。
6. 客户端从 usage 接口查询剩余次数、成功/失败次数和明细。

## 二、管理角色与任务

| 角色 | 主要任务 | 后端权限 |
| --- | --- | --- |
| `SUPER_ADMIN` | 全局配置和所有业务 | 全部 |
| `PARTNER` | 创建自己的不可变套餐版本、制卡、原卡续费、作废卡密、查看自己的销售记录 | 不能查看全局财务、公司 Token 池和其他代理的数据 |
| `CUSTOMER` | 仅使用客户端注册、登录、激活和消费 | 不能登录管理后台，不能制套餐或制卡 |

Vue 动态菜单只是交互层过滤。真实安全边界在 `AdminAuthInterceptor` 与 `@RequirePermission`，直接调用无权限接口同样会被拒绝。

### 邀请码与卡密的边界

邀请码适合当前代理销售模型，但不是授权凭证。每个 `PARTNER` 在升级时生成一个唯一邀请码；客户注册时可以不填，填写后服务端校验邀请码所属用户仍为有效代理，并将注册渠道写入独立归属表。归属用于代理获客统计、售后分流和财务分析，不会让客户获得代理权限，也不会自动开通套餐。

卡密仍是套餐权益的唯一兑换凭证。邀请码和卡密允许在注册页同时填写：先完成短信注册，再单独激活卡密；即使卡密因库存等原因激活失败，已经成功的注册也不会回滚。注册归属保持历史事实，邀请码停用不会删除既有归属。

## 三、客户端接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/v1/card/activate` | 激活卡密；客户端不能篡改卡密面值或销售类型 |
| POST | `/api/v1/client/auth/sms/send` | 请求注册短信验证码 |
| POST | `/api/v1/client/auth/register` | 手机号、验证码和密码注册，自动领取可配置试用 |
| POST | `/api/v1/client/auth/login` | 手机号、密码登录并校验单电脑绑定 |
| POST | `/api/v1/client/auth/logout` | 注销会话，保留电脑绑定 |
| POST | `/api/v1/client/auth/unbind-device` | 解绑电脑并使当前会话失效 |
| GET | `/api/v1/client/account/profile` | 套餐、到期时间、剩余次数 |
| GET | `/api/v1/client/account/usage` | 成功/失败次数及分页明细 |
| GET | `/api/v1/client/account/card` | 查询卡密、套餐、到期时间和专属小号使用情况 |
| GET | `/api/v1/client/resources/status` | 可用资源数量和账号上限，不泄漏凭证 |
| POST | `/api/v1/client/resources/acquire` | 领取短效加密资源 |
| POST | `/api/v1/client/resources/report` | 上报使用结果并完成扣次/免责 |

登录后的客户端请求必须同时携带 Sa-Token 请求头、`X-PDK-Phone` 和 `X-PDK-Device-ID`。

## 四、MySQL 自动初始化

`application.yml` 同时启用两层机制：

- JDBC URL 使用 `createDatabaseIfNotExist=true`，解决数据源连接发生在 SQL 脚本之前、空环境数据库尚不存在的问题。
- `spring.sql.init.mode=always` 与 `schema-locations=classpath:schema-mysql.sql`，保证每次 Spring Boot 启动都会检查表和种子。

脚本可重复执行。套餐和管理账号按主键/唯一账号更新，演示资源用 `WHERE NOT EXISTS` 条件插入。任何 SQL 失败都会中止启动，避免应用在残缺表结构上提供服务。

套餐续费由制卡代理在后台调用 `/api/v1/admin/card/{cardKey}/renew`。续费保持原卡密不变、顺延有效期和次数，并强制新增 `RENEWAL` 销售记录。代理通过 `/api/v1/admin/sales/list` 只能查看自己的记录；超级管理员可查看全局财务。

## 五、短信策略

短信发送通过统一 `SmsSender` 接口实现。只有显式启用 `local` Spring Profile 才使用固定验证码；验证码在数据库中仅保存摘要，具有发送频率限制、有效期和一次性消费语义。`aliyun` 适配器及配置入口已预留，取得阿里云短信凭据、签名与模板后再接入官方 SDK。生产环境必须使用 `PDK_SMS_PROVIDER=aliyun`，不得启用 `PDK_SMS_FIXED_CODE_ENABLED`。

## 六、仍需在生产部署前完成

- 完成阿里云短信官方 SDK 调用、回执监控和供应商错误码映射。
- 超级管理员种子账号仍兼容旧 SHA-256 摘要；应迁移为 BCrypt/Argon2，并建立改密、锁定和二次验证流程。客户及代理密码已经使用 BCrypt。
- 把 AES 根密钥移出源码，放入 KMS 或安全配置中心，并建立密钥轮换版本。
- Redis 采用高可用部署；资源租约依赖 Redis，登录设备校验在 Redis 故障时可回退 MySQL。
- 增加每日配额定时重置和更完整的端到端自动化测试。
- 替换 SQL 中 `LOCAL-DEMO-SLOT-01` 假资源，禁止在生产环境使用演示 Token。
