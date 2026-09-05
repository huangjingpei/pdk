"""PyQt5 启动阶段升级编排；领域逻辑仍由 ClientUpdateManager 提供。"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from PyQt5 import QtCore
from PyQt5.QtCore import QThread, Qt, pyqtSignal
from PyQt5.QtWidgets import QMessageBox, QProgressDialog, QWidget

from .config import UpdateConfig
from .errors import UpdateError
from .manager import ClientUpdateManager
from .background import BackgroundUpdateService, UpdateState, UpdateInfo


class _Worker(QThread):
    succeeded = pyqtSignal(object)
    failed = pyqtSignal(object)
    progressed = pyqtSignal(int)

    def __init__(self, task: Callable[[Callable[[int, int], None]], Any], parent=None) -> None:
        super().__init__(parent)
        self._task = task

    def run(self) -> None:
        try:
            result = self._task(self._progress)
        except BaseException as exc:
            self.failed.emit(exc)
            return
        self.succeeded.emit(result)

    def _progress(self, done: int, total: int) -> None:
        percent = 0 if total <= 0 else min(100, max(0, int(done * 100 / total)))
        self.progressed.emit(percent)


@dataclass(frozen=True)
class StartupUpdateResult:
    continue_startup: bool
    updater_started: bool = False


def _run_task(label: str, task: Callable[[Callable[[int, int], None]], Any],
              parent: QWidget | None = None, determinate: bool = False) -> tuple[Any, BaseException | None]:
    dialog = QProgressDialog(label, "", 0, 100 if determinate else 0, parent)
    dialog.setWindowTitle("客户端升级")
    dialog.setWindowModality(Qt.ApplicationModal)
    dialog.setCancelButton(None)
    dialog.setMinimumDuration(0)
    dialog.setAutoClose(False)
    result: list[Any] = []
    error: list[BaseException] = []
    worker = _Worker(task, dialog)
    worker.succeeded.connect(lambda value: result.append(value))
    worker.failed.connect(lambda exc: error.append(exc))
    worker.progressed.connect(dialog.setValue)
    worker.finished.connect(dialog.accept)
    worker.start()
    dialog.exec_()
    worker.wait()
    worker.deleteLater()
    dialog.deleteLater()
    return (result[0] if result else None, error[0] if error else None)


def run_startup_update(device_id: str, expected_version: str,
                       parent: QWidget | None = None) -> StartupUpdateResult:
    """登录窗出现前执行升级策略；强制更新不可跳过。"""
    try:
        config = UpdateConfig.load()
        if config.version != expected_version:
            raise UpdateError(
                f"版本配置不一致：core={expected_version}，client-update.json={config.version}"
            )
    except BaseException as exc:
        QMessageBox.critical(parent, "升级配置错误", str(exc))
        return StartupUpdateResult(False)
    if not config.enabled:
        return StartupUpdateResult(True)

    manager = ClientUpdateManager(config, device_id)
    try:
        decision, check_error = _run_task(
            "正在安全检查客户端更新…", lambda _progress: manager.check(), parent,
        )
        if check_error is not None:
            cached = manager.cached_required()
            if cached is not None:
                QMessageBox.critical(
                    parent, "必须升级",
                    "当前版本已低于最低可运行版本，且暂时无法连接升级服务器。\n\n"
                    "请恢复网络连接后重新启动客户端。",
                )
                return StartupUpdateResult(False)
            print(f"[升级] 检查失败，本次允许继续启动：{check_error}")
            return StartupUpdateResult(True)
        if not decision or not decision.get("hasUpdate"):
            return StartupUpdateResult(True)

        required = decision.get("updatePolicy") == "REQUIRED"
        target = decision.get("targetVersion") or "新版本"
        notes = str(decision.get("releaseNotes") or "暂无更新说明")
        box = QMessageBox(parent)
        box.setIcon(QMessageBox.Warning if required else QMessageBox.Information)
        box.setWindowTitle("必须升级" if required else "发现新版本")
        box.setText(f"发现版本 {target}")
        box.setInformativeText(notes + ("\n\n当前版本必须升级后才能继续使用。" if required else "\n\n是否现在下载并安装？"))
        install_button = box.addButton("立即升级", QMessageBox.AcceptRole)
        later_button = box.addButton("退出程序" if required else "暂不更新", QMessageBox.RejectRole)
        box.setDefaultButton(install_button)
        box.exec_()
        if box.clickedButton() is later_button:
            return StartupUpdateResult(not required)

        downloaded, download_error = _run_task(
            f"正在下载并验证 {target}…",
            lambda progress: manager.download_and_verify(decision, progress),
            parent, determinate=True,
        )
        if download_error is not None:
            QMessageBox.critical(parent, "升级失败", str(download_error))
            return StartupUpdateResult(not required)
        package, final_decision = downloaded
        try:
            manager.launch_updater(final_decision, package)
        except BaseException as exc:
            QMessageBox.critical(parent, "无法启动升级器", str(exc))
            return StartupUpdateResult(not required)
        QMessageBox.information(parent, "准备安装", "升级包验证通过。客户端将退出并由独立升级器完成安装。")
        return StartupUpdateResult(False, updater_started=True)
    finally:
        manager.close()


class UpdateService(QtCore.QObject):
    """``BackgroundUpdateService`` 的 Qt 包装：把后台状态变化转成信号。

    后台线程通过回调触发 ``stateChanged`` / ``progressChanged``，Qt 会自动以
    队列连接（QueuedConnection）把信号投递到 GUI 线程，因此可在槽里安全操作控件。
    """

    stateChanged = QtCore.pyqtSignal(object, object, object)  # (UpdateState, UpdateInfo|None, error|None)
    progressChanged = QtCore.pyqtSignal(float)

    def __init__(self, manager, device_id: str, enabled: bool = True, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._svc = BackgroundUpdateService(
            manager, device_id,
            on_state=self._on_state, on_progress=self._on_progress, enabled=enabled,
        )

    def _on_state(self, state: UpdateState, info: UpdateInfo | None, error: str | None) -> None:
        self.stateChanged.emit(state, info, error)

    def _on_progress(self, frac: float) -> None:
        self.progressChanged.emit(frac)

    # 透传控制面
    def start(self) -> None:
        self._svc.start()

    def stop(self) -> None:
        self._svc.stop()

    def apply_pending(self, resume_payload: dict | None = None) -> bool:
        return self._svc.apply_pending(resume_payload)

    def skip_version(self) -> None:
        self._svc.skip_version()

    def download_now(self) -> None:
        self._svc.download_now()

    @property
    def state(self) -> UpdateState:
        return self._svc.state

    @property
    def has_pending(self) -> bool:
        return self._svc.has_pending()

    @property
    def pending_target_version(self) -> str | None:
        return self._svc.pending_target_version()


def integrate(app, window: QWidget, manager, device_id: str,
              status_bar: QWidget | None = None, enabled: bool = True) -> UpdateService:
    """把零打扰升级接入应用：启动后后台检查可选更新，READY 时非阻塞提示，退出时应用。

    调用方仍应保留「启动时必须更新(REQUIRED)就在登录前阻断」的逻辑；本助手只负责
    可选更新的静默下载与「重启以应用」的轻量提示。
    """
    service = UpdateService(manager, device_id, enabled=enabled, parent=window)
    prompted: dict[str, bool] = {"value": False}

    def on_state(state: UpdateState, info: UpdateInfo | None, error: str | None) -> None:
        if state in (UpdateState.READY_TO_INSTALL, UpdateState.AVAILABLE_REQUIRED) and info is not None:
            if state == UpdateState.READY_TO_INSTALL and prompted["value"]:
                return
            if state == UpdateState.READY_TO_INSTALL:
                prompted["value"] = True
            required = state == UpdateState.AVAILABLE_REQUIRED
            _show_update_ready(window, info, required,
                               on_restart=lambda: _apply_and_quit(app, service))

    service.stateChanged.connect(on_state)

    def on_quit() -> None:
        if service.has_pending():
            try:
                service.apply_pending()
            except Exception:
                pass

    app.aboutToQuit.connect(on_quit)
    service.start()
    return service


def _show_update_ready(window: QWidget, info: UpdateInfo, required: bool,
                       on_restart: Callable[[], None]) -> None:
    """非阻塞的「更新就绪」提示；用户可立即重启，也可忽略并在退出时自动应用。"""
    box = QMessageBox(window)
    box.setModal(False)
    box.setWindowTitle("客户端更新")
    box.setIcon(QMessageBox.Warning if required else QMessageBox.Information)
    box.setText(f"新版本 {info.target_version} 已就绪")
    notes = (info.release_notes or "包含稳定性与安全更新").strip()
    box.setInformativeText(notes + ("\n\n必须重启客户端以完成本次更新。" if required
                                     else "\n\n重启客户端即可完成更新；本次会话可继续使用。"))
    restart_btn = box.addButton("重启并更新" if not required else "立即重启更新", QMessageBox.AcceptRole)
    if not required:
        later_btn = box.addButton("稍后", QMessageBox.RejectRole)
        later_btn.clicked.connect(box.reject)
    box.setDefaultButton(restart_btn)
    restart_btn.clicked.connect(lambda: (box.accept(), on_restart()))
    box.show()


def _apply_and_quit(app, service: UpdateService) -> None:
    if service.apply_pending():
        app.quit()
