# PDK 客户端升级方案设计蓝图（业界最好用）

本文档定义 PDK 桌面客户端「业界最好用」升级体验的目标、四大支柱、现状盘点，
以及分阶段的落地排期。当前工程已具备**安全底座**（Ed25519 验签、原子替换、回滚、
健康检查、灰度/强制策略、C++ 原生升级器），差距主要在**用户体验**与**发布闭环**。

> 本轮（2026-09-03）已完成第一支柱「零打扰更新体验」的核心实现，见文末「已交付清单」。

---

## 1. 目标体验（用户视角）

- **可选更新**：用户几乎无感。客户端在后台静默检查、下载、验签；只在下载完成后，
  于状态栏/托盘出现一个轻量「重启以应用 vX」入口；用户继续手头工作，**退出或重启时
  才真正完成安装**。绝不弹出强制中断对话框。
- **强制更新**：必须在登录前阻断（这是正确行为），但给出清晰进度与「失败可回滚」的保障。
- **断电/杀软/空间不足**：任何失败都回到旧版本，用户无感知数据丢失；升级器保留
  `.backup-*` / `.failed-*` 供排查。
- **发布侧**：开发者一条命令完成「打包 → 签名 → 发布到后端」，不必进管理后台手工操作。
- **带宽**：大客户端只下发变更文件的二进制补丁（增量更新）。
- **无缝续接**：更新后新进程自动恢复旧窗口/页面/登录态，用户像没断过。

---

## 2. 四大支柱

| 支柱 | 用户价值 | 当前状态 | 优先级 |
| --- | --- | --- | --- |
| **零打扰更新体验** | 可选更新不打断 | ✅ 本轮已交付核心 | P0（已完成本轮） |
| **一键发布闭环** | 开发者效率 | ⚠️ 仅本地打包，发布需进后台 | P1 |
| **增量更新** | 省带宽/提速 | ❌ 仅整包替换 | P2 |
| **无缝续接** | 更新如丝滑 | 🟡 已预留 resume 钩子 | P1 |

---

## 3. 现状盘点

### 已实现（安全与事务底座，业界水准）
- 服务端：策略/发布/构件/事件四表，Ed25519 策略与构件双签名，灰度、最低版本强制、
  短效下载令牌、HEAD/Range/ETag、匿名幂等事件。
- C++ 原生升级器 `native_updater`（`pdk_updater.exe` / `pdk_updater_gui.exe`）：
  **等父进程退出 → 验签 → 备份 → 原子替换 → 启动新版本 → 健康检查 → 失败回滚**，
  且纯 C++、无 Python/Qt 依赖。
- PyQt 端：登录前检查、Range 断点下载、SHA-256 + Ed25519 验签、主程序退出后交接。
- 运维 GUI：可扫描本地包、安装、回滚到任意历史备份版本。
- 构建脚本 `scripts/build_update_package.py`：生成整包 ZIP + `update-manifest.json` + 可选签名清单。

### 仍缺（差距）
1. **UX 打断**：可选更新也是阻塞弹窗 + 立即安装，不符合「零打扰」。
2. **两套并行实现**：`client_update/manager.py`（可复用库）与
   `client-pyqt/update_client.py`（旧内联，main.py 在用）接口相同但未统一，
   维护易漂移。→ 新代码统一以**鸭子类型接口**编写，自动兼容两者。
3. **发布需手工进后台**：缺少「直传后端发布」的一条命令。
4. **整包替换**：PyInstaller onedir 客户端常达数百 MB，无增量。
5. **无状态续接**：更新后新进程不恢复旧现场（仅预留 `PDK_UPDATE_RESUME` 钩子）。

---

## 4. 本轮交付：零打扰更新体验

### 设计要点
- 新增 `client_update/background.py`：`BackgroundUpdateService`（**与 Qt 无关**，
  仅用标准库 + 线程），把「检查 → 下载 → 验签 → 待安装」移到后台线程。
- 鸭子类型接口（不绑定具体 manager）：`check()` / `cached_required()` /
  `download_and_verify(decision, progress)` / `launch_updater(decision, package, resume_payload=None)`。
  两套 `ClientUpdateManager` 均满足。
- 状态机 `UpdateState`：`CHECKING → AVAILABLE_OPTIONAL/REQUIRED → DOWNLOADING →
  READY_TO_INSTALL`（或 `NO_UPDATE` / `ERROR` / `DISABLED`）。
- **核心差异**：可选更新不再弹窗，而是后台下载完成后置为 `READY_TO_INSTALL`，
  由 UI 给出非阻塞提示；**进程退出时（`aboutToQuit`）才拉起升级器完成安装**。
- **待装恢复**：启动时若缓存里已有验签过的包，直接置 `READY_TO_INSTALL`，不重复下载。
- **防重复拉起**：`apply_pending()` 成功后标记 `consumed`，二次调用返回 `False`。

### 集成方式（`client_update/qt_flow.py`）
- `UpdateService(QtCore.QObject)`：把后台状态变化转成 `stateChanged` / `progressChanged`
  信号（Qt 自动队列投递到 GUI 线程）。
- `integrate(app, window, manager, device_id)`：启动后后台检查可选更新；`READY` 时弹出
  **非模态**「重启并更新」对话框（可忽略，退出时仍自动应用）；`app.aboutToQuit` 时应用待装。

### main.py 行为变化
- 启动检查仍执行；**强制更新(REQUIRED)维持登录前阻断**（正确行为）。
- 可选更新：不再弹「立即升级/暂不更新」；改为 `window.show()` 后由 `integrate()` 在后台
  静默下载，就绪后非阻塞提示，退出时自动应用。
- `launch_updater` 新增 `resume_payload` 参数（默认 `None`，向后兼容），通过
  `PDK_UPDATE_RESUME` 环境变量传给新进程，为「无缝续接」预留钩子。

### 验证
- 新增 `client-pyqt/test_background_update.py`：虚拟 manager 覆盖 9 组场景
  （无更新 / 可选→就绪 / 强制→就绪 / 网络失败回退缓存强制 / 无缓存→ERROR /
  篡改包被拒 / 初始化恢复待装 / 应用待装拉起+只拉一次 / 禁用）。**26/26 通过**。

---

## 5. 其余支柱排期与方案

### P1 一键发布闭环
- 扩展 `scripts/build_update_package.py` 或新增 `scripts/publish_release.py`：
  在现有 `--emit-job` 基础上，调用后端「上传构件 + 创建发布 + 置为 PUBLISHED」接口
  （携带 HMAC 事件令牌与构建配置），一条命令完成发布。
- 需后端补充：带鉴权的「发布创建」API（管理权限已存在，复用即可）。
- 验收：开发者改完代码 → 一条命令 → 客户端下次检查即见新版本。

### P1 无缝续接
- 复用本轮预留的 `PDK_UPDATE_RESUME`：客户端退出前把「当前窗口/页面/登录态摘要」序列化为
  JSON 传给 `apply_pending(resume_payload=...)`；新进程启动早期读取该变量恢复现场。
- 应用层只需实现「读取 `PDK_UPDATE_RESUME` → 恢复路由/状态」一小段，升级框架已就绪。

### P2 增量更新
- 服务端在发布时基于上一版本生成二进制补丁（bsdiff/courgette 思路），构件类型增加
  `DELTA`；客户端/升级器支持 `apply-patch` 到当前安装目录得到新版本，再走既有验签/替换。
- 风险点：补丁生成需稳定基线、补丁失败须回退整包；建议先做「整包为主、增量可选」的双通道。

---

## 6. 已交付文件索引

| 文件 | 改动 |
| --- | --- |
| `client_update/background.py` | **新增** 后台静默升级服务（核心） |
| `client_update/qt_flow.py` | 新增 `UpdateService` 与 `integrate()` |
| `client_update/__init__.py` | 导出 `BackgroundUpdateService` / `UpdateState` / `UpdateInfo` |
| `client_update/manager.py` | `launch_updater` 支持 `resume_payload` |
| `client-pyqt/update_client.py` | `launch_updater` 支持 `resume_payload`（向后兼容） |
| `client-pyqt/main.py` | 可选更新改为后台静默；退出时应用待装 |
| `client-pyqt/test_background_update.py` | **新增** 状态机单测（26/26） |
| `docs/UPGRADE_SOLUTION_DESIGN.md` | **新增** 本文档 |

> 注：`client_update/background.py` 不依赖 Qt，因此可被任何客户端（PyQt5/6、未来 C++ 宿主
> 的 Python 嵌入层）复用；真正的「丝滑」取决于应用层是否消费 `PDK_UPDATE_RESUME`。
