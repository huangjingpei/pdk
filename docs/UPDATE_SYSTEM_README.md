# 多业务客户端升级系统开发规格

> 文档状态：需求与技术规格，尚未实现。
>
> 本文用于指导后续开发，不代表当前仓库已经存在升级接口、数据表、管理页面或客户端安装器。

相关基线文档：

- [多业务 appId/bizId 方案](./MULTI_BIZ_APPID_SOLUTION.md)
- [编译、数据库初始化与部署手册](./BUILD_AND_DEPLOY.md)
- [升级系统验收指南](./UPGRADE_TESTING_GUIDE.md)

## 1. 建设目标

为当前 PDK 系统增加统一的客户端升级能力，并按 `appId` 隔离版本、升级包、发布策略和统计数据。

首期需要覆盖：

- PDD 客户端：`appId=1`；
- ZHIBO_AI 客户端：`appId=2`；
- ZHIBO_LIVE 客户端：`appId=3`；
- Windows x64 客户端的完整包更新；
- 可选更新、强制更新、灰度发布、暂停发布和故障版本处置；
- 管理后台上传、校验、发布、停用、查看统计和审计；
- 客户端在登录前检查更新，下载后验证完整性与发布者签名，再交给独立安装器更新。

这里列出的 1、2、3 是首批验收业务，不是代码白名单。以后在 `pdk_business` 新增 appId 后，只要为其创建升级策略和发布数据，同一套升级系统就应能够管理，不得增加 `if (appId == ...)` 分支。

首期不包含：

- 二进制差分更新；
- 后端、管理网页或数据库自身的升级；
- macOS/Linux/Android 的真实安装流程；
- 自动生成安装包、自动代码签名和 CI/CD 发布；
- 把每个手机号、代理商或终端客户都建成一个 appId。

## 2. 与当前项目的关系

### 2.1 appId 的准确含义

当前项目已有明确的三层标识：

| 标识 | 含义 | 升级系统用途 |
| --- | --- | --- |
| `bizId` | 服务端内部业务主键 | 数据表关联、索引、权限和统计隔离 |
| `appId` | 客户端公开的数字应用标识 | 客户端请求版本线；例如 PDD=1、ZHIBO_LIVE=3 |
| `bizCode` | 稳定的服务端业务编码 | 日志、后台展示和部署配置 |

升级系统必须复用 `pdk_business`，不再创建来源文档中的 `apps` 表。

这里的“不同客户（appId）端”应理解为不同客户端产品或业务。普通用户、代理和客户仍由现有用户及后台账号体系管理，不能为每个用户分配 appId。若以后同一业务需要 OEM 包、代理专版或不同下载渠道，应增加独立的 `distributionChannel`，而不是复制业务或复用 appId。

### 2.2 与业务开关的关系

升级检查必须在登录前可调用，而且不能因为 `pdk_business.status=DISABLED`、业务 Handler 未加载或当前部署未启用该业务而完全不可访问。原因是旧客户端可能必须先升级，才能恢复登录或迁移到新服务。

因此后续实现时：

- 普通业务接口继续使用“业务可用”校验；
- 升级检查只验证 appId 存在，读取该业务的升级发布数据；
- 管理端发布仍执行管理员身份、业务范围和发布权限校验；
- 可以单独关闭某个 appId 的升级服务，但不能借用业务运行开关表达该状态。

### 2.3 项目协议基线

- 客户端通过 `X-PDK-App-ID` 携带数字 appId；
- 登录前也可以携带 `X-PDK-Device-ID`，用于稳定灰度分桶，但设备 ID 不是授权凭证；
- 管理端使用现有 Admin Sa-Token，不采用来源文档中的 Spring Security/JWT 假设；
- JSON 接口统一使用 `CommonResult<T>`：`code/message/data/timestamp`；
- 管理端分页沿用项目现有 MyBatis-Plus 页码约定，页码从 1 开始；
- 所有升级数据表保存 `biz_id`，不在明细表重复保存客户端传入的 appId。

### 2.4 新业务接入规则

新增业务不会自动获得可发布升级能力。标准流程是：

1. 先在 `pdk_business` 创建稳定 appId/bizCode；
2. 为 STABLE（可选 BETA）创建默认关闭的客户端升级策略；
3. 配置允许的平台、架构、公钥和存储范围；
4. 上传并发布第一个完整版本；
5. 验收通过后打开该 appId 的升级检查；
6. 最后再按需要启用服务端最低版本拦截。

新业务没有升级策略时，检查接口返回“升级服务未配置”，不能回落到 PDD，也不能读取其他业务版本。

## 3. 核心术语

| 术语 | 定义 |
| --- | --- |
| 版本发布 Release | 某个 appId 的一个不可变版本，例如 1.8.0 |
| 构件 Artifact | 版本对应的具体平台、架构和包类型文件 |
| 升级协议版本 | 检查响应、签名原文和包清单的协议代号 |
| updater 版本 | 独立安装器自身版本，与主程序版本分开 |
| 版本线 Channel | `STABLE` 或 `BETA`；生产客户端默认只使用 STABLE |
| 当前版本 | 客户端正在运行的版本 |
| 最新版本 | 当前 appId、版本线下已发布且适配平台的最高版本 |
| 最低可运行版本 | 低于该版本的客户端必须升级后才能继续业务操作 |
| 可选更新 | 有新版本，但当前版本仍可继续使用 |
| 强制更新 | 当前版本低于最低可运行版本，业务功能必须被阻止 |
| 灰度比例 | 可选更新向稳定设备分桶开放的比例 |
| 撤回发布 | 停止向未升级设备推荐问题版本，不删除历史记录和文件证据 |

## 4. 版本规则

### 4.1 首期格式

首期只接受严格的 `MAJOR.MINOR.PATCH`，例如 `1.7.0`、`2.0.3`：

- 三段都必须是非负十进制整数；
- 不接受 `v1.7.0`、`1.7`、`1.7.0.1`；
- 不使用字符串字典序比较；
- BETA 通过独立 `channel` 表达，不在版本号中使用 `-beta`；
- 同一 appId 下版本号不可重复，也不能删除后重新使用。

生产客户端的 channel 必须固化在构建配置或受签名配置中，普通用户不能在设置页把 STABLE 切换成 BETA。开发调试版可以选择 channel，但请求日志必须明确标识。

如果以后需要完整 SemVer 预发布语义，必须统一升级服务端、管理端、Python SDK、PyQt 客户端和安装器后再开放，不能只修改服务端正则。

### 4.2 更新判定

服务端是升级策略的权威来源。判定顺序：

1. 按 appId 解析 bizId；
2. 校验客户端版本、平台、架构和版本线；
3. 读取当前 appId/版本线/平台策略及 `minimumSupportedVersion/mandatoryReleaseId`；
4. 校验 mandatoryReleaseId 指向同业务、同版本线、平台构件完整且仍为 PUBLISHED 的全量稳定发布；
5. 如果当前版本低于最低版本，返回 mandatoryReleaseId 对应构件和 `REQUIRED`，且不受其他灰度发布影响；
6. 否则查找高于当前版本的最高兼容 PUBLISHED Release；设备命中该 Release 灰度时返回 `OPTIONAL`；
7. 其他情况返回 `NONE`。

不能仅用 `isMandatory=true` 表示强制更新。最低可运行版本能够准确表达“1.5.0 以下必须更新，1.5.0～1.7.0 可以稍后更新”。

### 4.3 灰度分桶

- 灰度比例范围为 0～100；
- 服务端先用专用稳定密钥对 `bizId + 原始deviceId` 做 HMAC-SHA256，得到业务内匿名设备标识；
- 再使用 `appId + releaseId + 匿名设备标识` 计算 0～9999 的确定性桶，灰度百分比按万分桶判断；
- 同一设备重复检查必须得到相同结果；
- 强制更新忽略灰度比例；
- deviceId 缺失时不能随机抖动，首期按“不命中可选灰度”处理；
- 灰度从低比例调高不会让已命中的设备退出。

灰度 HMAC 密钥与客户端加密根盐、管理员 pepper 分离管理，并在所有后端实例保持一致。普通密钥轮换会改变分桶结果，因此必须提供密钥版本和专项迁移方案；数据库与日志只保存匿名标识及密钥版本，不保存原始 deviceId。

## 5. 发布生命周期

版本发布使用以下状态：

```text
DRAFT -> READY -> PUBLISHED -> SUSPENDED -> ARCHIVED
```

| 状态 | 含义 | 客户端是否可见 |
| --- | --- | --- |
| `DRAFT` | 编辑元数据或上传构件中 | 否 |
| `READY` | 文件、哈希、签名和平台信息已通过发布前校验 | 否 |
| `PUBLISHED` | 正式参与更新判定 | 是 |
| `SUSPENDED` | 发现问题，停止向新设备推荐 | 已获得的短效下载地址可自然过期 |
| `ARCHIVED` | 仅保留历史、审计和统计 | 否 |

允许的状态转换必须固定：

| 当前状态 | 允许进入 |
| --- | --- |
| `DRAFT` | `READY`；删除未发布草稿 |
| `READY` | `DRAFT`（重新编辑）、`PUBLISHED` |
| `PUBLISHED` | `SUSPENDED`、`ARCHIVED` |
| `SUSPENDED` | `PUBLISHED`（重新确认后恢复）、`ARCHIVED` |
| `ARCHIVED` | 无，禁止恢复和删除 |

发布必须是显式动作。上传完成不等于发布，不能让客户端读到半上传、无签名或缺少目标平台构件的版本。

发布后以下内容不可直接覆盖：版本号、文件内容、文件大小、SHA-256、数字签名、平台、架构和包类型。

发现文件错误时应暂停该版本并新建更高版本。常规回滚采用“前向修复”：用更高版本号重新发布上一版稳定代码。只有紧急场景才允许签名的降级策略，而且必须单独审计，首期不建议开放普通后台按钮。

## 6. 数据模型规格

后续实现建议增加四张核心表，名称使用当前项目 `pdk_` 前缀。本文只定义职责和约束，不提供可直接执行的 DDL。

### 6.1 `pdk_client_update_policy`

保存某个业务版本线的运行策略，而不是某个安装包的信息。建议策略维度为 `(biz_id, channel, platform, arch)`。

| 字段 | 要求 |
| --- | --- |
| `biz_id` | 关联 `pdk_business.id` |
| `channel/platform/arch` | 明确适用的客户端范围 |
| `update_enabled` | 是否允许检查和签发下载地址，默认关闭 |
| `minimum_supported_version` | 当前最低可运行版本，与具体最新 Release 解耦 |
| `mandatory_release_id` | 低版本客户端必须安装的稳定目标；版本不得低于最低支持版本 |
| `server_enforcement_enabled` | 服务端是否执行 HTTP 426 拦截，默认关闭 |
| `offline_grace_hours` | 检查服务故障时允许的产品宽限期 |
| `check_interval_seconds` | 客户端建议检查间隔，只是提示值 |
| `policy_revision` | 每次策略变化递增，用于缓存、并发和审计 |
| `updated_by/updated_at` | 最近变更操作人和时间 |

唯一键：`(biz_id, channel, platform, arch)`。提高最低版本时必须同时指定可安装的 mandatoryReleaseId，两个字段在同一事务和同一审计动作中生效。强制目标必须为 100% 全量发布，不能指向灰度、暂停或归档版本。暂停当前强制目标前必须先原子切换到另一个合格发布，不能产生“服务端拒绝旧版，但客户端无包可装”的锁死状态。

### 6.2 `pdk_client_release`

一个 appId 的一个版本发布。

| 字段 | 要求 |
| --- | --- |
| `id` | 全局主键，即 releaseId |
| `biz_id` | 必填，关联 `pdk_business.id` |
| `version` | 严格三段版本号 |
| `version_major/minor/patch` | 用于可靠排序，禁止依赖字符串排序 |
| `channel` | `STABLE/BETA` |
| `minimum_protocol_version` | 能识别本发布的最低升级协议版本 |
| `minimum_updater_version` | 能安全安装本发布的最低独立 updater 版本 |
| `release_notes` | 用户可见更新说明，按纯文本或受限 Markdown 渲染 |
| `status` | 发布生命周期状态 |
| `rollout_percentage` | 0～100 |
| `published_at` | 真正发布时填写，不等于上传时间 |
| `created_by/updated_by/published_by` | 后台操作人 |
| `created_at/updated_at` | 审计时间 |

约束和索引：

- 唯一键：`(biz_id, version)`；
- 查询索引：`(biz_id, channel, status, published_at)`；
- 同一个 appId 可以保留多个 PUBLISHED 历史版本，但更新判定只选择最高可用版本。

### 6.3 `pdk_client_artifact`

一个 Release 可以有多个平台构件。关键字段包括 releaseId、bizId、platform、arch、packageType、原始文件名、安全存储键、文件大小、SHA-256、签名算法、签名值、签名公钥版本、上传状态和创建时间。

约束和索引：

- 唯一键：`(release_id, platform, arch, package_type)`；
- 查询索引：`(biz_id, platform, arch, status)`；
- 存储键全局唯一；
- 文件名只用于展示，不能作为服务器磁盘路径；
- 删除发布记录时不得物理级联删除已发布构件。

### 6.4 `pdk_client_update_event`

记录检查和安装关键事件，用于统计及故障定位：

```text
CHECKED, OFFERED, DOWNLOAD_STARTED, DOWNLOAD_COMPLETED,
VERIFY_SUCCEEDED, VERIFY_FAILED, INSTALL_STARTED,
INSTALL_SUCCEEDED, INSTALL_FAILED
```

只保存必要信息：bizId、releaseId、artifactId、匿名化 deviceIdHash、原版本、目标版本、平台、事件、错误分类、客户端时间、服务端时间和 checkRequestId。不得保存手机号、完整设备 UUID、下载签名或本地完整路径。

索引至少覆盖 `(biz_id, created_at)`、`(biz_id, release_id, event_type, created_at)`；checkRequestId 使用幂等唯一键或事件级组合唯一键。

原始事件需要配置保留期，建议 90 天后汇总或删除；管理员发布审计不随事件清理。清理任务必须按时间和批次执行，不能长事务锁住在线检查。

### 6.5 数据库初始化约定

当前项目仍采用 `schema-mysql.sql` 空库自动初始化。后续实现时应把升级表的最终 `CREATE TABLE` 和索引直接加入该文件，不增加历史兼容 ALTER。

但升级系统上线后将产生必须保留的发布与审计数据。下一次再修改表结构前，应先评估引入 Flyway/Liquibase，不能通过删库重建处理生产升级数据。

## 7. 客户端 API 契约

### 7.1 检查更新

```http
GET /api/v1/client/updates/check?currentVersion=1.7.0&platform=WINDOWS&arch=X64&channel=STABLE&protocolVersion=1&updaterVersion=1.0.0
X-PDK-App-ID: 3
X-PDK-Device-ID: <稳定设备UUID>
```

此接口登录前可访问，不要求 Sa-Token、手机号或卡密。`X-PDK-App-ID` 必须显式携带，不使用 PDD 缺省回落。

成功响应仍使用 `CommonResult`：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "checkRequestId": "UC-...",
    "protocolVersion": 1,
    "appId": 3,
    "bizCode": "ZHIBO_LIVE",
    "channel": "STABLE",
    "platform": "WINDOWS",
    "arch": "X64",
    "currentVersion": "1.7.0",
    "updaterVersion": "1.0.0",
    "hasUpdate": true,
    "updatePolicy": "REQUIRED",
    "reason": "BELOW_MINIMUM_SUPPORTED_VERSION",
    "latestVersion": "1.9.0",
    "minimumSupportedVersion": "1.8.0",
    "mandatoryReleaseId": 120,
    "targetVersion": "1.8.0",
    "policyRevision": 12,
    "checkIntervalSeconds": 21600,
    "offlineGraceHours": 24,
    "policyIssuedAt": "2026-08-29T10:05:00+08:00",
    "policyExpiresAt": "2026-08-30T10:05:00+08:00",
    "policySignatureAlgorithm": "Ed25519",
    "policySigningKeyId": "client-policy-2026-01",
    "policySignature": "<Base64>",
    "releaseId": 120,
    "releaseNotes": "修复推流稳定性问题",
    "publishedAt": "2026-08-29T10:00:00+08:00",
    "serverTime": "2026-08-29T10:05:00+08:00",
    "artifact": {
      "artifactId": 301,
      "platform": "WINDOWS",
      "arch": "X64",
      "packageType": "ZIP",
      "fileName": "zhibo-live-1.9.0-windows-x64.zip",
      "fileSize": 104857600,
      "sha256": "<64位小写十六进制>",
      "signatureAlgorithm": "Ed25519",
      "signature": "<Base64>",
      "signingKeyId": "client-release-2026-01",
      "downloadUrl": "<短时有效且不可猜测的地址>",
      "downloadUrlExpiresAt": "2026-08-29T10:15:00+08:00"
    },
    "eventToken": "<仅用于本次升级事件上报的短效令牌>"
  },
  "timestamp": 1787978700000
}
```

`targetVersion/releaseId/artifact` 始终描述本次实际要安装的同一个发布；`latestVersion` 只是当前版本线最高可见版本。强制场景下 targetVersion 可以低于正在灰度的 latestVersion，例如上例强制安装稳定的 1.8.0，而不是绕过灰度安装 1.9.0。客户端不得自行用 latestVersion 替换 targetVersion。

策略签名至少覆盖协议版本、appId、channel、platform、arch、policyRevision、updatePolicy、minimumSupportedVersion、mandatoryReleaseId、targetVersion、policyIssuedAt 和 policyExpiresAt，不覆盖每次变化的 checkRequestId、eventToken 和短效 downloadUrl。客户端只有在策略签名、公钥用途和有效期都正确时才能写入本地策略缓存。

无更新不是错误，返回 `code=200`、`hasUpdate=false`、`updatePolicy=NONE`、`artifact=null`。没有匹配平台构件也返回明确 `reason=NO_COMPATIBLE_ARTIFACT`，不能误发其他平台文件。

升级策略未配置或 `updateEnabled=false` 同样返回 `code=200/updatePolicy=NONE`，reason 分别为 `UPDATE_POLICY_NOT_CONFIGURED`、`UPDATE_SERVICE_DISABLED`。此时不得启用服务端最低版本拦截。

### 7.2 下载构件

检查接口返回短效下载地址。该地址可以 302 跳转到对象存储/CDN签名 URL，或由 Nginx `X-Accel-Redirect` 发送本地不可公开目录中的文件。

不建议继续保留来源文档中的公开 `/files/**` 目录，也不提供“根据版本字符串直接下载”的永久地址。客户端下载地址应关联 artifactId、有效期和签名，服务器响应支持 Range、Content-Length、ETag 和断点续传。

### 7.3 上报升级事件

```http
POST /api/v1/client/updates/events
X-PDK-App-ID: 3
Content-Type: application/json
```

请求包含 checkRequestId、eventToken、artifactId、事件类型、原版本、目标版本和标准化错误分类。上报接口登录前可用，必须幂等并限流；事件失败不能导致安装结果回滚。

### 7.4 服务端最低版本拦截

仅依靠客户端显示“强制更新”可以被旧版或修改版客户端绕过。后续第二阶段应要求业务请求携带：

```text
X-PDK-Client-Version
X-PDK-Platform
X-PDK-Arch
```

服务端发现版本低于当前 appId 的最低可运行版本时返回 HTTP 426 和业务码 `42600`。更新检查、下载、事件上报、注销和必要的只读诊断接口必须在豁免名单内，避免客户端被彻底锁死。

当前 `WebMvcConfig` 会统一拦截 `/api/v1/client/**`。后续实现时必须把检查和事件上报加入登录前排除项，下载则只校验短效下载凭证；不能要求用户先登录或先激活卡密才能获得强制升级包。

## 8. 管理端 API 与页面规格

建议管理端路由统一放在 `/api/v1/admin/client-updates/**`。建议契约如下，具体 DTO 命名可以在实现阶段确定，但职责不能重新合并：

| 方法与路径 | 职责 |
| --- | --- |
| `GET /releases` | 按 bizId、channel、状态、版本和时间分页查询 |
| `POST /releases` | 创建 DRAFT，只写发布元数据 |
| `PUT /releases/{releaseId}` | 只允许编辑 DRAFT/READY 的可变元数据 |
| `POST /releases/{releaseId}/artifacts/upload-session` | 创建短效上传会话和 artifact 草稿 |
| `POST /artifacts/{artifactId}/complete` | 完成上传，触发服务端文件识别、摘要、扫描和签名 |
| `POST /releases/{releaseId}/ready` | 校验构件集合完整并进入 READY |
| `POST /releases/{releaseId}/publish` | 显式发布 READY 版本 |
| `POST /releases/{releaseId}/suspend` | 暂停新检查和新下载地址签发 |
| `POST /releases/{releaseId}/resume` | 重新确认后恢复发布 |
| `POST /releases/{releaseId}/archive` | 归档历史版本 |
| `GET/PUT /policies/{bizId}` | 查询或更新版本线/平台策略，使用 policyRevision 乐观锁 |
| `GET /events`、`GET /statistics` | 查看升级事件和聚合统计 |

所有创建、完成、状态变化和策略修改请求必须携带调用方生成的幂等 requestId；同一 requestId 重试返回原结果，不能重复创建构件、重复发布或重复写有效审计。

管理能力包括：

1. 按业务、版本线、状态、平台和时间分页查询发布；
2. 创建草稿；
3. 上传或登记构件；
4. 读取服务端计算的大小、SHA-256、签名和文件识别结果；
5. 将完整构件集合从 DRAFT 提交为 READY；
6. 发布版本并设置灰度比例和说明；
7. 在确认存在兼容构件后，单独调整最低支持版本和服务端拦截开关；
8. 调整灰度比例；
9. 暂停或归档发布；
10. 查看检查、下载、验证和安装成功率；
11. 查看完整操作审计。

删除接口只允许删除从未发布且没有审计依赖的 DRAFT。发布过的版本只能暂停或归档。

### 8.1 上传模式

- 本地开发：可以由 Spring Boot 接收 multipart 后写入隔离临时目录；
- 生产：优先由后端创建短效上传会话，浏览器直传私有对象存储，完成后通知后端校验；
- 无论哪种模式，SHA-256、文件大小、Magic、包清单和签名结果都必须由可信服务端或受控发布流水线确认，不能接受浏览器声称“已经校验”；
- 对象存储 multipart 未完成分片必须有自动过期清理策略；
- complete 重试必须幂等，不能生成多个 AVAILABLE 构件。

### 8.2 页面布局

- 顶部必须选择业务，显示 appId、bizCode 和业务描述；
- 发布列表显示版本、渠道、最低支持版本、灰度、状态、发布时间和构件完整度；
- 策略区单独显示升级开关、最低支持版本、服务端拦截、宽限期和策略 revision；
- 版本详情按平台/架构列出文件大小、SHA-256、签名、公钥版本和存储状态；
- 发布确认框明确展示“哪些旧版本将被强制更新”；
- 暂停、归档、调整最低版本和紧急策略必须填写原因；
- 不在浏览器回显服务器绝对路径、存储密钥或签名私钥。

### 8.3 权限决策

升级包等同于可在客户电脑执行的代码，风险高于制套餐或制卡。

| 角色 | 首期权限 |
| --- | --- |
| `SUPER_ADMIN` | 查看全部业务；创建、上传、发布、暂停和归档 |
| `PARTNER` | 默认无升级管理权限；如有需要只能单独授权只读查看所属业务 |

后续如需委托，新增独立权限 `client-update:view/create/publish/suspend`，不能复用 `card:create`、`package:create` 或 `business:edit`。所有查询继续通过当前 `AdminBusinessScope` 强制 bizId 数据范围。

## 9. 文件存储与发布安全

### 9.1 存储要求

- 开发环境可以使用本地目录，但必须位于应用工作目录之外；
- 生产优先使用阿里云 OSS、S3 兼容存储或其他私有对象存储；
- 对象键应包含 bizId、releaseId、artifactId 和内容摘要，不使用原始文件名拼路径；
- 上传先进入隔离临时区，校验完成后原子转入不可变发布区；
- 元数据响应禁止缓存或使用短缓存，带内容摘要的构件可以长期 immutable 缓存；
- 数据库与对象存储需要定期对账，发现记录存在但文件丢失时自动禁止发布。

### 9.2 安装包访问边界

appId、deviceId 和升级检查接口都是登录前信息，不能证明调用者已付费。短效下载 URL、限流和 eventToken 主要用于防盗链、降低滥用和关联事件，不等价于用户授权。

首期应把安装包视为“可被获取但不可伪造”的分发物，真正的软件使用权继续由手机号登录、设备绑定、卡密许可证、套餐到期和服务端业务鉴权控制。安装包和客户端代码中不得包含可绕过这些鉴权的长期密钥。

若未来代理 OEM 包必须限制在特定渠道，应引入独立、可吊销的 distributionCredential 和 distributionChannel，并纳入签名及下载令牌；appId 仍只选择业务，不能承担保密凭证职责。

### 9.3 完整性与真实性

SHA-256 只能发现传输损坏；如果攻击者同时替换文件和数据库哈希，客户端仍会信任恶意文件。因此每个构件还必须具备数字签名：

- 推荐 Ed25519；
- 客户端内置一个或多个受信公钥；
- 签名覆盖文件摘要以及 appId、版本、平台、架构等关键元数据；
- 私钥不放在数据库、源码、前端或普通应用配置中；
- 公钥轮换通过 signingKeyId 管理，旧公钥至少保留到对应版本退出支持周期；
- 客户端必须先校验 SHA-256，再校验数字签名，任一步失败都禁止安装。

Windows 的 Authenticode 代码签名可以作为额外保护，但不能替代升级协议自己的签名校验。

首期签名原文必须固定规范，避免服务端和不同语言客户端各自拼接。建议使用 UTF-8、LF 和固定字段顺序：协议标识、appId、version、platform、arch、packageType、fileSize、sha256；枚举统一大写，摘要统一 64 位小写十六进制，字段不做本地化。规范一旦发布必须通过协议版本升级才能改变。

文件构件签名与升级策略签名可以使用同一签名服务，但建议使用不同用途的 keyId 和私钥权限，避免获得“修改强制策略”的权限同时能够签署任意程序文件。

### 9.4 上传校验

- 扩展名、声明 MIME、文件 Magic 和包内清单必须一致；
- 文件名、版本、平台和 appId 不能只信任浏览器表单；
- ZIP 根目录必须包含受签名元数据约束的 `update-manifest.json`，至少声明 appId、version、platform、arch、入口程序和允许写入的相对文件列表；
- 包清单同时声明 protocolVersion、minimumUpdaterVersion 和安装布局版本；服务端不能向能力不足的 updater 下发不可安装构件；
- 限制单文件大小和压缩解包后的总大小，防止压缩炸弹；
- 禁止路径穿越、符号链接逃逸和覆盖已有对象；
- 可执行文件进入发布区前执行恶意软件扫描；
- 上传、发布、暂停和签名失败均写管理员审计，但日志不输出签名私钥或完整临时下载令牌。

## 10. 客户端升级流程

### 10.1 启动顺序

1. 客户端从自身构建配置读取固定 appId、当前版本、平台和架构；
2. 生成或读取稳定设备 UUID；
3. 在登录前调用更新检查；
4. `NONE`：进入正常登录；
5. `OPTIONAL`：展示说明，允许“立即更新/稍后”；
6. `REQUIRED`：禁止登录和业务操作，只保留重试、下载、退出和诊断；
7. 下载到临时文件，支持断点续传；
8. 校验文件大小、SHA-256 和 Ed25519；
9. 启动独立 updater，主程序退出；
10. updater 原子切换版本并启动新程序；
11. 新程序完成启动自检后上报成功；失败则恢复上一版本并上报失败。

检查更新失败时：

- 没有已知强制策略时，不应因升级服务器短暂不可用永久阻塞客户端；
- 本地已缓存并验签的强制策略仍在生效时，继续阻止业务并提示网络恢复后重试；
- 客户端不能把本地时间作为强制策略是否到期的唯一依据，应使用最近一次服务端时间校准；
- 任何降级放行策略必须由产品明确设置宽限期，不能在代码里无限期 fail-open。

### 10.2 Windows 安装器要求

运行中的 EXE 不能可靠覆盖自身。PyQt 主程序只负责检查、下载和验证，实际替换由独立 updater 执行。

推荐采用版本目录：

```text
app/
  current.json
  versions/1.7.0/
  versions/1.8.0/
  updater/
```

安装步骤必须支持等待主进程退出、再次验证包、解压到新目录、原子切换 current、启动新版本、健康确认和失败回滚。不要直接在现有安装目录逐文件覆盖，否则断电或文件占用会留下半升级状态。

updater 自身升级必须采用下一次运行生效的旁路替换或单独引导程序，不能让正在运行的 updater 覆盖自己。发布改变包布局、签名协议或入口规则前，必须先发布兼容旧格式的 updater 过渡版本。

### 10.3 现有客户端首次接入

当前已发布客户端尚未实现本升级协议，服务端无法凭空弹出升级窗口，也不能立即强制要求它携带版本 Header。首次上线必须分阶段：

1. 先通过现有人工交付渠道发布“桥接版本”，内含更新检查、签名验证和独立 updater；
2. 此阶段 `serverEnforcementEnabled=false`，业务接口继续兼容缺少版本 Header 的旧客户端；
3. 统计桥接版本覆盖率，并为仍在使用旧版的客户提供明确人工升级期限；
4. 覆盖率达到产品门槛后，先启用仅记录不拦截的版本监控；
5. 确认 mandatoryRelease 可安装、下载服务健康后，再启用 HTTP 426 强制拦截；
6. 最后才关闭缺少客户端版本 Header 的兼容行为。

跳过桥接阶段会导致老客户端既不会检查更新，又被服务端拒绝全部业务请求。

## 11. 错误处理建议

| 业务码 | 含义 |
| --- | --- |
| `40050` | appId 缺失、格式错误或 Header 冲突 |
| `40450` | appId 对应业务不存在 |
| `40321` | 普通业务接口已停用；不能用于阻止更新检查 |
| `40090` | 平台、架构、版本线等检查参数错误 |
| `40490` | 发布或构件不存在 |
| `40990` | 当前发布状态不允许该操作 |
| `40991` | 版本号已存在或低于已发布版本 |
| `41390` | 升级包超过大小限制 |
| `42290` | 版本格式、文件识别、摘要或签名校验失败 |
| `42600` | 当前客户端版本低于最低可运行版本 |
| `42990` | 更新检查、下载签名或事件上报过于频繁 |
| `50390` | 文件存储、签名或下载服务暂不可用 |

客户端必须同时判断 HTTP 状态和 CommonResult.code。下载文件和 302 响应不使用 CommonResult 包装。

## 12. 配置边界

后续实现至少需要以下部署配置域，但密钥不得写入仓库：升级功能总开关、本地或对象存储类型、临时上传区和发布区、外部下载 URL 前缀、签名服务或密钥引用、下载 URL 有效期、单文件最大大小、允许的平台/架构/包类型、接口限流以及构件保留周期。每个 appId 的升级开关、最低版本、灰度和宽限期属于数据库运营策略，不放入 application.yml。

当前 `spring.servlet.multipart.max-file-size=10MB` 不足以承载常见 PyQt 安装包。实现时需要单独评估并调整上传限制，不能照搬来源文档的固定 100MB，也不能无限制开放。

## 13. 监控与运营指标

至少按 appId、版本、平台和时间统计：当前版本分布、检查次数、策略分布、灰度命中率、下载开始/完成率、摘要/签名失败、安装成功率、强制更新阻断量、构件流量和存储错误。

事件统计可能因为客户端离线、崩溃或用户终止而不完整，只能用于运营判断，不能作为财务账务数据。

## 14. 推荐开发顺序

1. 固化版本规则、发布状态和数据库最终结构；
2. 实现文件存储抽象、摘要、签名和不可变构件；
3. 实现管理端草稿、上传、READY 和发布状态机；
4. 实现登录前检查、短效下载和事件上报；
5. 实现 PyQt 更新提示与独立 Windows updater；
6. 增加服务端最低版本拦截；
7. 增加灰度、统计、暂停发布和故障版本处置；
8. 完成 `UPGRADE_TESTING_GUIDE.md` 中的验收后才允许生产启用。

## 15. 开发完成定义

- appId/bizId 全链路隔离；
- 上传不等于发布，发布状态机有效；
- 可选更新、最低版本强制更新和稳定灰度结果正确；
- 客户端校验 SHA-256 和数字签名；
- Windows updater 支持原子安装和失败回滚；
- 问题版本可以暂停且不会继续向新设备下发；
- 管理员权限、业务范围和操作审计完整；
- 更新检查在登录前和业务停用场景仍可用；
- 数据库、文件存储、CDN缓存和事件统计行为经过专项验收；
- 文档、客户端接入说明和运维回滚手册与实际实现一致。
