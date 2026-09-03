# 矩阵转发客户端升级接入说明

本客户端已经接入 PDK 客户端升级系统。升级检查发生在登录窗口之前，不依赖登录 Token；检查、验签、下载和安装与推流业务完全解耦。

## 1. 当前客户端配置

构建配置位于 `config/client-update.json`：

- `appId=3`，对应服务端业务 `ZHIBO_LIVE`。
- 当前版本为 `1.7.0`。
- 发布平台为 `WINDOWS/X64`，完整包格式为 ZIP。
- 主入口为 `zhibodou.exe`。
- 独立安装器文件名由 `updaterExecutable` 指定，公共模块不绑定当前客户端名称。
- 策略公钥和构件公钥已经使用当前 PDK 服务端生成的公钥；服务端轮换签名密钥时，客户端必须先同时内置新旧公钥并发布过渡版本。
- 开发环境默认读取 `PDK_BASE_URL`；也可以用 `PDK_UPDATE_BASE_URL` 只覆盖升级服务器。

生产环境应把服务端地址配置为 HTTPS，例如：

```powershell
$env:PDK_BASE_URL = "https://pdk.example.com"
```

## 2. 升级模块边界

| 模块 | 职责 |
| --- | --- |
| `client_update/config.py` | 加载并严格校验客户端差异配置 |
| `client_update/api.py` | 调用公开检查接口、上报升级遥测 |
| `client_update/security.py` | Ed25519 策略与构件验签 |
| `client_update/manager.py` | 可信策略缓存、断点下载、SHA-256 校验、交接 updater |
| `client_update/qt_flow.py` | PyQt5 启动提示与强制/可选升级交互 |
| `client_update/updater.py` | 主进程退出后二次验签、安全解压、目录切换、健康检查和回滚（C++ 升级器缺失时的回退路径） |
| `client_update/health.py` | 新版本启动健康握手 |

> **实际安装由 Windows 原生升级器执行。** 客户端默认调用 `native_updater` 产出的 `pdk_updater.exe`，找不到时才回退到上表中的 Python `updater.py`。两者安全策略一致，但只有原生升级器配套的 `pdk_updater_gui.exe` 提供人工选择版本、回滚到指定历史版本的能力。详见 [Windows 原生升级器 native_updater](./NATIVE_UPDATER.md)。

其他 Python/PyQt 客户端复用时，只需复制 `client_update` 包、提供自己的 `client-update.json`，并在 QApplication 创建后调用 `run_startup_update()` 和 `mark_update_healthy()`。

## 3. 发布一个新版本

以下以发布 `1.8.0` 为例。

第一步，同时修改两个位置的版本号：

```text
core/config.py                   APP_VERSION = "1.8.0"
config/client-update.json       "version": "1.8.0"
```

`build_exe.py` 会校验两者一致，防止把旧配置打进新版。

第二步，构建推荐的 one-folder 发布版：

```powershell
cd E:\zhibodou
python build_exe.py --windowed
```

构建产物中必须存在：

```text
E:\zhibodou\dist\zhibodou\
├─ zhibodou.exe
├─ zhibodou_updater.exe
├─ client-update.json
└─ _internal\...
```

第三步，执行冻结态自检：

```powershell
E:\zhibodou\dist\zhibodou\zhibodou.exe --selfcheck
```

第四步，生成服务端可校验的完整升级 ZIP：

```powershell
python E:\pdk\scripts\build_update_package.py `
  --source E:\zhibodou\dist\zhibodou `
  --output E:\zhibodou\dist\updates\zhibodou-1.8.0-windows-x64.zip `
  --app-id 3 `
  --version 1.8.0 `
  --entry-point zhibodou.exe `
  --protocol-version 1 `
  --minimum-updater-version 1.0.0
```

第五步，在管理后台选择业务 `ZHIBO_LIVE / appId=3`：

1. 创建 `1.8.0` 的 STABLE Release。
2. 最低协议填写 `1`，最低 Updater 填写 `1.0.0`。
3. 上传刚生成的 ZIP，完成服务端校验和签名。
4. 将 Release 变更为 READY，再发布为 PUBLISHED。
5. 如需强制升级，在运行策略中配置最低可运行版本和强制目标 Release。

## 4. 安装与回滚过程

1. 客户端验证服务端策略签名。
2. 在 `%LOCALAPPDATA%\PDK\3\updates` 断点下载升级包。
3. 校验文件大小、SHA-256 和构件 Ed25519 签名。
4. 把独立 updater 复制到用户缓存并退出主程序。
5. updater 再次验签，将新包安全解压到安装目录同级暂存目录。
6. 旧安装目录改名为备份，新目录切换到正式路径。
7. 启动新版并等待带随机 nonce 的健康标记。
8. 新版健康检查失败时，终止新版、恢复旧目录并重新启动旧版。

第 6 步产生的备份目录命名为 `.{安装目录名}.backup-<pid>-<tick>`，与安装目录同级。它是 `pdk_updater_gui.exe`「历史备份」列表的数据来源，可从中选择任意一个历史版本回滚；回滚后当前版本同样会成为一个新的备份目录，因此支持来回切换。

运行期账号、卡密和设备 ID 均存放在用户数据目录，不在程序安装目录内，因此完整目录替换不会删除用户授权数据。

## 5. 联调开关

```powershell
# 临时关闭启动升级检查
$env:PDK_UPDATE_ENABLED = "false"

# 单独指定升级 API 地址
$env:PDK_UPDATE_BASE_URL = "http://127.0.0.1:8080"

# 单独指定下载缓存
$env:PDK_UPDATE_CACHE = "D:\pdk-update-cache"
```

开发源码运行时会执行检查和下载，但不会替换源码目录；自动安装必须在 PyInstaller 发布版中验证。
