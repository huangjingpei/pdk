# Windows 原生升级器 native_updater

`native_updater/` 是用 C++20 编写的独立 Windows 升级器，不依赖 Python、Qt 或任何 UI 框架。它负责升级事务中最危险的那一段：**替换正在运行、无法自我覆盖的客户端进程**。

PyQt 主程序（`client-pyqt/`）负责检查更新、下载和验签，然后交给本升级器完成原子替换、启动新版本、健康确认与失败回滚。

产出两个可执行文件：

| 可执行文件 | 子系统 | 用途 |
| --- | --- | --- |
| `pdk_updater.exe` | Console | 命令行模式，供客户端程序化调用（写 `job.json` 后传 `--job`） |
| `pdk_updater_gui.exe` | Windows (GUI) | 纯 Win32 窗口，供运维人工选择安装或回滚某个特定版本 |

两者复用同一套核心事务代码，安全策略完全一致。

## 1. 升级事务流程

无论是命令行还是 GUI 触发，安装都走同一个 `pdk::run_update`：

1. **验签** —— 计算升级包 SHA-256，并用 Ed25519 公钥验签；
2. **等待父进程退出** —— 最多 90 秒，超时则放弃并上报失败；
3. **安全解压** —— 校验 ZIP 条目无路径穿越 / 无盘符绝对路径 / 无符号链接，并二次校验包内 `update-manifest.json` 与内嵌构建配置；
4. **备份当前版本** —— 把 `install_root` 整体改名为同级的 `.{name}.backup-<pid>-<tick>`；
5. **原子替换** —— 解压好的暂存目录改名为 `install_root`；
6. **启动新客户端** —— 通过环境变量注入健康检查的 `nonce` 与文件路径；
7. **健康检查** —— 等待客户端写回 `{nonce, version}`；
8. **成功**则删除备份，**失败**则把新版本隔离为 `.failed-*`、把备份改回 `install_root` 并重启旧版本。

第 4 步产生的备份目录，就是 GUI「回滚到某个特定版本」的数据来源。

## 2. 签名协议

与服务端 `ClientUpdateService.artifactCanonical` **完全一致**，三端共用同一 canonical 串：

```text
PDK-ARTIFACT-V1\n{appId}\n{version}\n{platform}\n{arch}\n{type}\n{size}\n{sha256}
```

- 算法：Ed25519
- 公钥格式：base64 编码的 SPKI-DER
- 公钥**不会**通过升级检查接口下发，客户端必须自带受信公钥（命令行由 `job.json` 的 `publicKey` 提供，GUI 由 `updater-gui.json` 提供）

## 3. 构建

依赖（nlohmann/json、miniz、Monocypher）由 CMake `FetchContent` 自动拉取，首次配置约需十几分钟，之后增量编译只要十几秒。

```bash
cd E:/pdk/native_updater
unset HTTP_PROXY http_proxy HTTPS_PROXY https_proxy ALL_PROXY all_proxy
cmake -S . -B build
cmake --build build --config Debug
```

> **必须 unset 代理变量。** 本机若同时存在大小写两份代理变量（`HTTP_PROXY` 与 `http_proxy`），MSBuild 在构造环境变量字典时会因重复键崩溃（MSB6001: CL.exe 命令行开关无效）。依赖已缓存时不需要联网，unset 不影响构建。

产物：`build/Debug/pdk_updater.exe` 与 `build/Debug/pdk_updater_gui.exe`。

## 4. 命令行用法

```text
pdk_updater.exe --job <job.json 路径> [--quiet]
pdk_updater.exe --interactive          # 弹出文件选择框挑 job.json
pdk_updater.exe --version | --help
```

`job.json` 由调用方（客户端）生成，字段：

| 字段 | 说明 |
| --- | --- |
| `schemaVersion` | 固定 `1` |
| `packagePath` | 升级包 ZIP 路径（相对路径按 job 文件所在目录解析） |
| `installRoot` | 安装根目录，必须绝对路径 |
| `targetVersion`、`appId`、`platform`、`arch`、`packageType` | 版本与平台标识，参与验签 |
| `fileSize`、`sha256` | 包大小与摘要 |
| `signature`、`publicKey` | Ed25519 签名与受信公钥 |
| `entryPoint` | 启动入口，相对 `installRoot` |
| `parentPid` | 调用方进程 ID，用于等待其退出 |
| `healthFile`、`healthNonce`、`healthTimeoutSeconds` | 健康检查契约 |
| `requireHealthCheck` | 为 `false` 时启动即视为成功（不回滚），由 GUI 使用 |
| `relaunchOnRollback` | 回滚后是否重启客户端 |
| `telemetry` | 可选，事件上报配置 |

退出码：`0` 成功；`2` 验签失败；其它非 0 为各类安装失败。

## 5. GUI 用法

```text
pdk_updater_gui.exe [--config <updater-gui.json 路径>]
```

未指定 `--config` 时，读取可执行文件同目录下的 `updater-gui.json`。模板见 `native_updater/updater-gui.example.json`。

| 配置项 | 必填 | 说明 |
| --- | --- | --- |
| `appId` | 是 | 业务应用 ID |
| `installRoot` | 是 | 安装根目录，**必须绝对路径** |
| `entryPoint` | 是 | 启动入口，相对 `installRoot` |
| `publicKey` | 是 | 受信 Ed25519 公钥（base64 SPKI-DER） |
| `packagesDir` | 否 | 本地版本包目录，GUI 扫描其中的 `*.job.json` 作为可安装版本 |
| `platform` / `arch` / `packageType` | 否 | 默认 `WINDOWS` / `X64` / `ZIP` |
| `healthTimeoutSeconds` | 否 | 默认 `60` |
| `relaunchOnRollback` | 否 | 默认 `true` |
| `requireHealthCheck` | 否 | 默认 `true`；置 `false` 时安装后不强制健康检查，旧版本备份会保留 |

界面能力：

- 顶部显示**当前安装版本**（读 `install_root/update-manifest.json`）
- **可安装版本列表** —— 扫描 `packagesDir` 下的 `*.job.json`
- **历史备份列表** —— 扫描 `install_root` 同级的 `.{name}.backup-*` 目录，选中后回滚到该版本
- 安装 / 回滚 / 浏览包 / 刷新 / 退出按钮，底部为只读日志框
- 安装与回滚均在**工作线程**执行，通过 `PostMessage` 回写日志，界面不卡死；事务进行中禁止关闭窗口，避免把安装目录留在半升级状态

## 6. 客户端如何触发，以及如何测试

### 触发链路

客户端（`client-pyqt/`）在**登录窗口之前**触发，共五步：

1. `main()` 构造 `ClientUpdateManager`，调用 `check()` → `GET /api/v1/client/updates/check`
2. 有更新时弹窗询问 → `download_and_verify()`：Range 断点下载 → 校验大小与 SHA-256 → Ed25519 构件验签
3. `launch_updater()`：优先找 C++ `pdk_updater.exe`，写 `update-job.json`（含签名、公钥、健康检查 nonce）并拉起它；找不到 exe 时回退 Python `updater.py`
4. **交接点**：客户端 `return 0` 退出。升级器等待 `parentPid` 退出（最多 90 秒）
5. 升级器完成事务：验签 → 备份旧版 → 原子替换 → 启动新客户端 → 等待健康文件 → 成功则清理备份 / 失败则回滚

`launch_updater()` 生成的 job **默认要求健康检查**（`requireHealthCheck` 未显式设置，服务端与 C++ 默认均为 `true`），
因此第 5 步的健康握手不是可选项。

### 健康检查契约（第 5 步的关键）

升级器通过环境变量把 `PDK_UPDATE_HEALTH_FILE` 与 `PDK_UPDATE_HEALTH_NONCE` 传给新客户端。
新客户端启动后**必须**写 JSON `{"nonce": ..., "version": ...}` 到该文件，否则超时后会被判定启动失败并回滚旧版本。

`client-pyqt/main.py` 已实现该契约；新接入的客户端必须照做。
注意升级器在健康检查通过后会**删除该健康文件并清理备份**，不要把它当作持久状态。

### 运行测试

```bash
cd E:/pdk/client-pyqt
python test_update_trigger.py            # 端到端（离线，20 项断言）
python test_update_trigger.py --keep     # 失败时保留现场目录便于排查
python test_update_trigger.py --online   # 用真实后端与真实构建配置做链路诊断
```

端到端测试真实走完「客户端触发 → 升级器接管 → 新版本启动 → 回报健康」，
并包含一个反向用例（篡改 SHA-256 必须被拒绝），避免只有成功路径造成的假阳性。

测试用**父子进程**模拟真实交接：子进程调用 `launch_updater()` 后立即退出，
父进程等待并断言，与客户端实际行为一致。

> 测试会真实替换安装目录，但它指向临时目录，**不要**把 `PDK_INSTALL_ROOT`
> 指向真实客户端安装位置。

### 在线链路的前提

`--online` 诊断会报告链路停在哪一步。要真正走通在线升级，客户端构建配置
（`client-pyqt/config/*.json`）必须内置与后端私钥对应的公钥：

```json
{
  "artifactPublicKeys": { "client-release-2026-01": "<构件公钥>" },
  "policyPublicKeys":   { "client-policy-2026-01":  "<策略公钥>" }
}
```

keyId 必须与后端配置一致。也可用环境变量 `PDK_UPDATE_ARTIFACT_PUBLIC_KEY` /
`PDK_UPDATE_POLICY_PUBLIC_KEY` 覆盖。`--online` 会在缺失时明确指出并给出修复提示。

## 7. 部署要求

- **升级器 exe 必须放在 `install_root` 之外。** 升级是整体替换安装目录，若 exe 在目录内会被自己覆盖。推荐与 `install_root` 平级放置。
- 入口点非 `.exe`/`.com` 时，升级器会自动选择启动器：`.py`/`.pyw` → `PDK_PYTHON_EXE`（回退 `py.exe`）；`.cmd`/`.bat` → `cmd.exe /c`；`.jar` → `PDK_JAVA_EXE -jar`（回退 `java.exe`）。
- 客户端需配合健康检查契约：若设置了环境变量 `PDK_UPDATE_HEALTH_NONCE`，必须向 `PDK_UPDATE_HEALTH_FILE` 写 JSON `{"nonce":..., "version":...}`，否则会被判定启动失败并回滚。

## 7. 与打包脚本配合

`scripts/build_update_package.py` 在产出 ZIP 的同时，可用 `--emit-job` 产出 GUI 可直接识别的签名清单：

```bash
python scripts/build_update_package.py \
  --source <客户端目录> --output <包.zip> \
  --app-id <appId> --version <版本> --entry-point <入口> \
  --emit-job --private-key <Ed25519 私钥> --public-key <公钥>
```

产出的 `<包名>.job.json` 放进 `packagesDir`，GUI 即可列出该版本并安装。把 `publicKey` 填进 `updater-gui.json` 即完成配置。

密钥由 `scripts/generate_update_keys.py` 生成（私钥 base64 PKCS8、公钥 base64 SPKI）。

相关文档：[升级系统开发规格](./UPDATE_SYSTEM_README.md) · [客户端升级配置](./CLIENT_UPDATE_CONFIGURATION.md) · [一期实施方案](./CLIENT_UPDATE_IMPLEMENTATION_PLAN.md)
