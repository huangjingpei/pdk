"""PyQt5 启动阶段升级编排；领域逻辑仍由 ClientUpdateManager 提供。"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from PyQt5.QtCore import QThread, Qt, pyqtSignal
from PyQt5.QtWidgets import QMessageBox, QProgressDialog, QWidget

from .config import UpdateConfig
from .errors import UpdateError
from .manager import ClientUpdateManager


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
