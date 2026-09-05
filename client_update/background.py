"""后台静默升级服务（与 UI 框架无关）。

设计目标：把「检查 → 下载 → 验签 → 待安装」尽量从主线程挪到后台线程，
可选更新不再弹强制弹窗，而是下载完成后在 UI 上给出一个非阻塞的「重启以应用」
入口；强制更新仍会显式暴露为 AVAILABLE_REQUIRED（由调用方决定是否在启动时阻断）。

本模块只依赖标准库 + ``client_update.errors.UpdateError``，不依赖 Qt / requests，
因此可以脱离 GUI 独立单测。它与具体的 ``ClientUpdateManager`` 实现解耦，只要求
manager 满足以下鸭子类型接口：

    manager.check() -> dict | None
    manager.cached_required() -> dict | None
    manager.download_and_verify(decision, progress) -> pathlib.Path
    manager.launch_updater(decision, package, resume_payload=None) -> None
    manager.cache_dir -> pathlib.Path | str   # 用于恢复待安装包

``client_update.manager.ClientUpdateManager`` 与
``client-pyqt.update_client.ClientUpdateManager`` 均满足上述接口。
"""
from __future__ import annotations

import json
import threading
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Optional

from .errors import UpdateError


class UpdateState(str, Enum):
    """升级服务状态机。"""

    IDLE = "idle"
    CHECKING = "checking"
    NO_UPDATE = "no_update"
    AVAILABLE_OPTIONAL = "available_optional"
    AVAILABLE_REQUIRED = "available_required"
    DOWNLOADING = "downloading"
    READY_TO_INSTALL = "ready_to_install"
    ERROR = "error"
    DISABLED = "disabled"

    @property
    def is_terminal(self) -> bool:
        return self in {
            UpdateState.NO_UPDATE,
            UpdateState.READY_TO_INSTALL,
            UpdateState.ERROR,
            UpdateState.DISABLED,
        }


@dataclass(frozen=True)
class UpdateInfo:
    """一次可用更新的元信息（供 UI 展示）。"""

    target_version: str
    policy: str
    release_notes: str
    from_version: str


StateCallback = Callable[[UpdateState, Optional[UpdateInfo], Optional[str]], None]
ProgressCallback = Callable[[float], None]


class BackgroundUpdateService:
    """在后台线程完成检查/下载/验签，把「打断用户」降到最低。

    典型用法::

        svc = BackgroundUpdateService(manager, device_id, on_state=ui_callback)
        svc.start()                       # 非阻塞，后台开始检查
        # ... 应用正常启动、用户正常使用 ...
        if svc.has_pending():             # 下载完成后变为 READY_TO_INSTALL
            svc.apply_pending()           # 拉起升级器，随后退出进程
    """

    def __init__(self, manager: Any, device_id: str,
                 on_state: Optional[StateCallback] = None,
                 on_progress: Optional[ProgressCallback] = None,
                 enabled: bool = True) -> None:
        self._manager = manager
        self._device_id = device_id
        self._on_state = on_state
        self._on_progress = on_progress
        self._enabled = enabled
        self._lock = threading.Lock()
        self._state = UpdateState.IDLE
        self._info: Optional[UpdateInfo] = None
        self._error: Optional[str] = None
        self._package: Optional[Path] = None
        self._decision: Optional[dict] = None
        self._progress = 0.0
        self._skipped: Optional[str] = None
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()
        self._consumed = False
        self._recover_pending()

    # ---- 读取（线程安全） ----

    @property
    def state(self) -> UpdateState:
        with self._lock:
            return self._state

    @property
    def info(self) -> Optional[UpdateInfo]:
        with self._lock:
            return self._info

    @property
    def error(self) -> Optional[str]:
        with self._lock:
            return self._error

    @property
    def progress(self) -> float:
        with self._lock:
            return self._progress

    def has_pending(self) -> bool:
        """是否已下载并验签完成、可以应用。"""
        with self._lock:
            return (self._state == UpdateState.READY_TO_INSTALL
                    and not self._consumed
                    and self._package is not None and self._package.is_file())

    def pending_target_version(self) -> Optional[str]:
        with self._lock:
            return self._info.target_version if self._state == UpdateState.READY_TO_INSTALL else None

    # ---- 控制 ----

    def start(self) -> None:
        """后台启动检查（非阻塞）。"""
        if not self._enabled:
            self._set(UpdateState.DISABLED, None, None)
            return
        with self._lock:
            already_ready = (self._state == UpdateState.READY_TO_INSTALL and self._package is not None
                             and self._package.is_file())
        if already_ready:
            # 上次已下载完成，直接复用，避免无谓重下。
            self._set(UpdateState.READY_TO_INSTALL, self._info, None)
            return
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="pdk-update", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        """请求停止（正在进行的下载不会被中断，仅阻止后续步骤）。"""
        self._stop.set()

    def download_now(self) -> None:
        """用户在可选更新提示下手动触发下载（可选更新默认也会自动下载）。"""
        with self._lock:
            decision = self._decision
            info = self._info
            downloading = self._state == UpdateState.DOWNLOADING
            ready = self._state == UpdateState.READY_TO_INSTALL
        if ready or downloading:
            return
        if decision is not None and info is not None:
            self._download(decision, info)

    def skip_version(self) -> None:
        """记住「此版本稍后」，本次会话不再自动下载该可选更新。"""
        with self._lock:
            if self._info is not None:
                self._skipped = self._info.target_version

    def apply_pending(self, resume_payload: Optional[dict] = None) -> bool:
        """应用待安装更新：拉起独立升级器并交还控制权（调用方随后退出进程）。

        ``resume_payload`` 会被透传给 ``launch_updater``，用于在更新后由新进程
        恢复用户现场（无缝续接）。
        """
        with self._lock:
            if self._consumed:
                return False
            decision = self._decision
            package = self._package
        if decision is None or package is None or not package.is_file():
            return False
        try:
            self._manager.launch_updater(decision, package, resume_payload=resume_payload)
        except UpdateError:
            raise
        except Exception as exc:  # 拉起失败不应吞掉，交由调用方决定
            raise UpdateError(f"无法启动升级器：{exc}") from exc
        with self._lock:
            self._consumed = True
        return True

    # ---- 内部 ----

    def _set(self, state: UpdateState, info: Optional[UpdateInfo] = None,
             error: Optional[str] = None) -> None:
        with self._lock:
            self._state = state
            self._info = info
            self._error = error
        if self._on_state is not None:
            self._on_state(state, info, error)

    def _report_progress(self, done: int, total: int) -> None:
        frac = 0.0 if total <= 0 else min(1.0, done / total)
        with self._lock:
            self._progress = frac
        if self._on_progress is not None:
            self._on_progress(frac)

    def _recover_pending(self) -> None:
        """若上一次已下载并验签的包仍在缓存，直接置为待安装，避免重复下载。"""
        try:
            cache = getattr(self._manager, "cache_dir", None)
            if cache is None:
                return
            decision_path = Path(cache) / "pending-update.json"
            if not decision_path.is_file():
                return
            decision = json.loads(decision_path.read_text(encoding="utf-8"))
            artifact = decision.get("artifact") or {}
            size = int(artifact.get("fileSize") or 0)
            package = Path(cache) / f"artifact-{artifact.get('artifactId')}.zip"
            if not package.is_file() or package.stat().st_size != size:
                return
            info = self._to_info(decision)
            with self._lock:
                self._state = UpdateState.READY_TO_INSTALL
                self._info = info
                self._decision = decision
                self._package = package
                self._progress = 1.0
        except Exception:
            return

    def _run(self) -> None:
        try:
            if self._stop.is_set():
                return
            self._set(UpdateState.CHECKING)
            try:
                decision = self._manager.check()
            except Exception as exc:  # 网络/验签失败：尝试缓存的强制策略
                cached = self._safe_cached_required()
                if cached is not None:
                    self._download(cached, self._to_info(cached))
                else:
                    self._set(UpdateState.ERROR, None, str(exc))
                return
            if self._stop.is_set():
                return
            if not decision or not decision.get("hasUpdate"):
                self._set(UpdateState.NO_UPDATE)
                return
            info = self._to_info(decision)
            required = str(decision.get("updatePolicy")) == "REQUIRED"
            self._set(UpdateState.AVAILABLE_REQUIRED if required else UpdateState.AVAILABLE_OPTIONAL, info)
            if not required and self._is_skipped(info.target_version):
                return  # 用户选择「此版本稍后」，不自动下载
            self._download(decision, info)
        except Exception as exc:
            self._set(UpdateState.ERROR, None, str(exc))

    def _download(self, decision: dict, info: UpdateInfo) -> None:
        self._set(UpdateState.DOWNLOADING, info)
        try:
            package = self._manager.download_and_verify(decision, self._report_progress)
        except Exception as exc:
            self._set(UpdateState.ERROR, info, str(exc))
            return
        if self._stop.is_set():
            return
        with self._lock:
            self._package = package
            self._decision = decision
            self._progress = 1.0
        self._set(UpdateState.READY_TO_INSTALL, info)

    def _is_skipped(self, version: str) -> bool:
        with self._lock:
            return self._skipped == version

    def _safe_cached_required(self) -> Optional[dict]:
        try:
            return self._manager.cached_required()
        except Exception:
            return None

    @staticmethod
    def _to_info(decision: dict) -> UpdateInfo:
        artifact = decision.get("artifact") or {}
        return UpdateInfo(
            target_version=str(decision.get("targetVersion") or artifact.get("version") or ""),
            policy=str(decision.get("updatePolicy") or "OPTIONAL"),
            release_notes=str(decision.get("releaseNotes") or ""),
            from_version=str(decision.get("fromVersion") or ""),
        )
