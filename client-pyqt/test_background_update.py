"""后台静默升级服务（client_update.background）的单元验证。

不依赖 Qt / requests / 真实后端：用一个鸭子类型的 FakeManager 模拟
ClientUpdateManager 接口，覆盖「零打扰」的核心状态机：

    无更新 / 可选→下载→就绪 / 强制→下载→就绪 / 网络失败回退缓存强制 /
    篡改包被拒 / 初始化恢复待装包 / 应用待装拉起升级器 / resume 透传

用法：
    python test_background_update.py
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
if str(REPO) not in sys.path:
    sys.path.insert(0, str(REPO))

from client_update.background import BackgroundUpdateService, UpdateState  # noqa: E402
from client_update.errors import UpdateError  # noqa: E402


CHECKS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    CHECKS.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""))
    return ok


def wait_state(svc: BackgroundUpdateService, states, timeout: float = 5.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if svc.state in states:
            return True
        time.sleep(0.01)
    return False


class FakeManager:
    """鸭子类型实现 ClientUpdateManager 接口，行为完全可控。"""

    def __init__(self, check_result=None, check_raises=None, cached_required=None,
                 tamper=False, artifact_id=9001, size=1024):
        self.cache_dir = Path(tempfile.mkdtemp(prefix="pdk-bg-test-"))
        self._check_result = check_result
        self._check_raises = check_raises
        self._cached_required = cached_required
        self._tamper = tamper
        self._artifact_id = artifact_id
        self._size = size
        self.check_calls = 0
        self.cached_calls = 0
        self.launches: list[tuple] = []

    def check(self):
        self.check_calls += 1
        if self._check_raises is not None:
            raise self._check_raises
        return self._check_result

    def cached_required(self):
        self.cached_calls += 1
        return self._cached_required

    def download_and_verify(self, decision, progress=None):
        if self._tamper:
            raise UpdateError("SHA-256 校验失败（模拟篡改）")
        artifact = decision.get("artifact") or {}
        target = self.cache_dir / f"artifact-{artifact.get('artifactId', self._artifact_id)}.zip"
        target.write_bytes(b"\x00" * int(artifact.get("fileSize", self._size)))
        if progress:
            progress(self._size, self._size)
        return target

    def launch_updater(self, decision, package, resume_payload=None):
        self.launches.append((decision, package, resume_payload))


def make_decision(policy="OPTIONAL", target="2.0.0", artifact_id=9001, size=1024):
    return {
        "appId": 3, "targetVersion": target, "updatePolicy": policy,
        "releaseNotes": "稳定性与安全更新", "fromVersion": "1.0.0",
        "hasUpdate": True,
        "artifact": {
            "artifactId": artifact_id, "fileSize": size, "sha256": "x" * 64,
            "signature": "sig", "signingKeyId": "client-release-2026-01",
            "platform": "WINDOWS", "arch": "X64", "packageType": "ZIP",
        },
    }


def run() -> int:
    print("=== 1 无可用更新 ===")
    m = FakeManager(check_result={"hasUpdate": False})
    svc = BackgroundUpdateService(m, "dev-1")
    svc.start()
    check("无更新 → NO_UPDATE", wait_state(svc, {UpdateState.NO_UPDATE}))
    check("未触发下载", svc.state != UpdateState.READY_TO_INSTALL)

    print("\n=== 2 可选更新：后台下载 → 就绪 ===")
    m = FakeManager(check_result=make_decision("OPTIONAL"))
    svc = BackgroundUpdateService(m, "dev-1")
    svc.start()
    check("可选 → READY_TO_INSTALL", wait_state(svc, {UpdateState.READY_TO_INSTALL}))
    check("has_pending 为真", svc.has_pending())
    check("待装目标版本正确", svc.pending_target_version() == "2.0.0", str(svc.pending_target_version()))
    check("进度为 1.0", abs(svc.progress - 1.0) < 1e-6, str(svc.progress))

    print("\n=== 3 强制更新：后台下载 → 就绪（暴露 AVAILABLE_REQUIRED）===")
    m = FakeManager(check_result=make_decision("REQUIRED"))
    states_seen = []
    svc = BackgroundUpdateService(m, "dev-1", on_state=lambda s, i, e: states_seen.append(s))
    svc.start()
    check("强制 → READY_TO_INSTALL", wait_state(svc, {UpdateState.READY_TO_INSTALL}))
    check("过程中出现过 AVAILABLE_REQUIRED", UpdateState.AVAILABLE_REQUIRED in states_seen)

    print("\n=== 4 网络失败：回退到缓存的强制策略 ===")
    m = FakeManager(check_raises=UpdateError("连接失败"),
                    cached_required=make_decision("REQUIRED", target="3.0.0"))
    svc = BackgroundUpdateService(m, "dev-1")
    svc.start()
    check("回退缓存强制 → READY", wait_state(svc, {UpdateState.READY_TO_INSTALL}))
    check("用过 cached_required", m.cached_calls >= 1)
    check("待装版本来自缓存=3.0.0", svc.pending_target_version() == "3.0.0")

    print("\n=== 5 网络失败且无缓存：进入 ERROR ===")
    m = FakeManager(check_raises=UpdateError("连接失败"), cached_required=None)
    svc = BackgroundUpdateService(m, "dev-1")
    svc.start()
    check("无缓存 → ERROR", wait_state(svc, {UpdateState.ERROR}))
    check("ERROR 时无待装", not svc.has_pending())

    print("\n=== 6 篡改包：download_and_verify 抛错 → ERROR ===")
    m = FakeManager(check_result=make_decision(), tamper=True)
    svc = BackgroundUpdateService(m, "dev-1")
    svc.start()
    check("篡改 → ERROR", wait_state(svc, {UpdateState.ERROR}))
    check("ERROR 时不产生待装", not svc.has_pending())

    print("\n=== 7 初始化恢复已下载的待装包（不重复检查/下载）===")
    m = FakeManager(check_result=make_decision())
    decision = make_decision()
    artifact = decision["artifact"]
    pkg = m.cache_dir / f"artifact-{artifact['artifactId']}.zip"
    pkg.write_bytes(b"\x00" * int(artifact["fileSize"]))
    (m.cache_dir / "pending-update.json").write_text(json.dumps(decision), encoding="utf-8")
    svc = BackgroundUpdateService(m, "dev-1")  # 不调用 start()
    check("初始化即 READY_TO_INSTALL", svc.state == UpdateState.READY_TO_INSTALL)
    check("恢复后 has_pending 为真", svc.has_pending())
    check("恢复过程未触发 check()", m.check_calls == 0)

    print("\n=== 8 应用待装：拉起升级器，且只拉起一次 ===")
    m = FakeManager(check_result=make_decision())
    svc = BackgroundUpdateService(m, "dev-1")
    svc.start()
    check("就绪后再应用", wait_state(svc, {UpdateState.READY_TO_INSTALL}))
    ok = svc.apply_pending(resume_payload={"view": "orders", "tab": 2})
    check("apply_pending 返回 True", ok)
    check("已调用 launch_updater", len(m.launches) == 1)
    check("resume_payload 透传", m.launches[0][2] == {"view": "orders", "tab": 2})
    check("应用后置为 consumed，has_pending=False", not svc.has_pending())
    ok2 = svc.apply_pending()
    check("二次 apply_pending 返回 False（不重复拉起）", ok2 is False and len(m.launches) == 1)

    print("\n=== 9 禁用：enabled=False → DISABLED ===")
    m = FakeManager(check_result=make_decision())
    svc = BackgroundUpdateService(m, "dev-1", enabled=False)
    svc.start()
    time.sleep(0.2)
    check("禁用 → DISABLED", svc.state == UpdateState.DISABLED)
    check("禁用时不检查", m.check_calls == 0)

    print("\n=== 结果 ===")
    failed = [n for n, ok, _ in CHECKS if not ok]
    passed = len(CHECKS) - len(failed)
    print(f"{passed}/{len(CHECKS)} 项通过")
    if failed:
        print("失败项: " + ", ".join(failed))
        return 1
    print("PASS ✅ 后台静默升级服务状态机全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(run())
