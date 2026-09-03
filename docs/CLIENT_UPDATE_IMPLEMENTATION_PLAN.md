# 客户端升级系统一期实施方案

本方案是 `UPDATE_SYSTEM_README.md` 与 `UPGRADE_TESTING_GUIDE.md` 的落地说明，覆盖服务端、管理后台、客户端和独立 Windows updater。实现不对白名单硬编码 appId；所有数据先由 `pdk_business.app_id` 解析为 bizId，再按 bizId 隔离。

本地密钥和 Spring Boot YAML 的完整配置步骤见 [客户端升级配置](./CLIENT_UPDATE_CONFIGURATION.md)。

## 已实现范围

### 服务端

- 四张核心表（策略、发布、构件、匿名升级事件）和一张管理操作幂等账本；空库由 `schema-mysql.sql` 创建。
- 严格三段版本与数值排序；发布状态固定为 `DRAFT → READY → PUBLISHED → SUSPENDED → ARCHIVED`。
- 上传会话、隔离存储、ZIP 路径/体积/清单校验、服务端 SHA-256 与 Ed25519 构件签名。
- 登录前检查：可选更新、最低版本强制目标、稳定 HMAC 灰度、缺设备不命中可选灰度、平台/Updater 协议兼容检查。
- 策略 Ed25519 签名、短效下载和事件令牌、HEAD/Range/ETag、匿名幂等事件。
- 管理 API、独立升级权限、业务范围、乐观 revision 和操作审计。
- 第二道 HTTP 426 门禁；桥接期对完全不携带版本 Header 的旧客户端暂时兼容。

### Vue 3 管理后台

菜单“客户端升级”包含：业务/appId 选择、版本列表、构件上传、就绪/发布/暂停/恢复/归档、强制策略原子保存、revision 冲突保护、升级事件和聚合指标。高风险动作强制填写原因。

### Python/PyQt 客户端

- 登录界面前检查；`OPTIONAL` 可稍后，`REQUIRED` 阻止进入主界面。
- 策略签名通过后才缓存；检查失败时只继续执行仍在宽限期内的已验签强制策略。
- Range 断点下载，依次校验大小、SHA-256、构件 Ed25519 签名。
- 主程序只负责校验和交接，独立 updater 等待主进程退出后安装。
- 默认调用 Windows 原生升级器 `native_updater` 产出的 `pdk_updater.exe`；找不到时回退到 Python 版 `updater.py`。两者都执行安全解压、再次验签、启动健康确认，失败恢复上一版本。
- 原生升级器采用「安装根整体替换 + 同级 `.backup-*` 隐藏目录」（而非 `versions/<version>` 多版本目录 + `current.json`）。旧版本以备份目录保留，因此可提供「回滚到某个特定版本」。详见 [Windows 原生升级器 native_updater](./NATIVE_UPDATER.md)。
- Python SDK 增加检查/事件方法，并为普通业务请求携带版本、平台和架构 Header。

## 部署步骤

1. 在隔离环境执行 `python scripts/generate_update_keys.py`。两套私钥进入密钥管理系统；构件公钥和策略公钥分别写入客户端固定构建配置或对应环境变量。
2. 配置 `PDK_UPDATE_STORAGE_ROOT` 到应用工作目录之外，并配置独立的 download/event/rollout 三套 HMAC secret。
3. 创建数据库并启动后端；确认五张升级相关表存在。生产环境应由 Nginx/私有对象存储承载大文件，本地 Spring 下载用于一期开发与验收。
4. 先发布带升级能力的桥接客户端，保持策略中的 `serverEnforcementEnabled=false`。
5. 后台新建 DRAFT，上传含根目录 `update-manifest.json` 的 ZIP，完成校验后依次 READY、PUBLISHED。
6. 覆盖率达到门槛后设置最低版本和 100% PUBLISHED mandatoryRelease；确认可下载、可验签、可安装后才打开 426。

客户端构建配置中的 `artifactPublicKeys` 和 `policyPublicKeys` 形如：

```json
{
  "artifactPublicKeys": {"client-release-2026-01": "<X.509 DER Base64>"},
  "policyPublicKeys": {"client-policy-2026-01": "<X.509 DER Base64>"}
}
```

ZIP 根目录清单最小示例：

```json
{
  "appId": 3,
  "version": "1.8.0",
  "platform": "WINDOWS",
  "arch": "X64",
  "protocolVersion": 1,
  "minimumUpdaterVersion": "1.0.0",
  "entryPoint": "main.py",
  "buildConfig": "zhibo-ai.json",
  "files": ["main.py", "pdk_client.py", "update_client.py"]
}
```

PyInstaller onedir 客户端不要手工维护 `files` 数组，可以使用仓库脚本直接生成完整包：

```powershell
python scripts\build_update_package.py `
  --source E:\zhibodou\dist\zhibodou `
  --output E:\zhibodou\dist\updates\zhibodou-1.1.0-windows-x64.zip `
  --app-id 2 `
  --version 1.1.0 `
  --entry-point zhibodou.exe
```

脚本会把 `update-manifest.json` 写在 ZIP 根目录，递归纳入 `_internal` 等运行依赖，拒绝路径穿越和符号链接，并输出最终 ZIP 的大小及 SHA-256；不会修改原始 dist 目录。

## 上线边界

- 当前本地文件下载实现适合开发和首轮验收；生产上线前应替换为私有 OSS/S3 + CDN 短签名跳转，避免大包占用应用实例连接和带宽。
- 真实生产私钥、对象存储桶和用户设备不得用于破坏性测试。
- 第一批真实发布必须完整执行 `UPGRADE_TESTING_GUIDE.md`，尤其是跨 appId、篡改、断电、空间不足、杀毒隔离、并发策略和暂停强制目标测试。
- 旧库升级不能依赖 `CREATE TABLE IF NOT EXISTS` 自动补列；正式投产前应引入 Flyway/Liquibase 并创建一次性迁移基线。
