"""PDK 客户端登录前升级检查、策略验签、断点下载与 updater 交接。"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import secrets
import subprocess
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Callable

import requests
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import load_der_public_key

from pdk_client import PdkApiClient


class UpdateError(RuntimeError):
    pass


class ClientUpdateManager:
    def __init__(self, client: PdkApiClient, build_config: dict[str, Any], device_id: str) -> None:
        self.client, self.config, self.device_id = client, build_config, device_id
        self.current_version = str(build_config.get("version", "1.0.0"))
        self.client.client_version = self.current_version
        self.updater_version = str(build_config.get("updaterVersion", "1.0.0"))
        self.cache_dir = Path(os.getenv("PDK_UPDATE_CACHE", Path.home() / ".pdk_client" / "updates"))

    def check(self) -> dict[str, Any]:
        response = self.client.check_update(
            self.current_version, device_id=self.device_id,
            channel=str(self.config.get("channel", "STABLE")), updater_version=self.updater_version,
        )
        if response.get("code") != 200:
            raise UpdateError(response.get("message") or "更新检查失败")
        data = response.get("data") or {}
        if data.get("hasUpdate"):
            self._verify_policy(data)
            self._cache_policy(data)
            self.report(data, "OFFERED")
        elif data.get("updatePolicy") == "NONE" and data.get("policySignature"):
            self._verify_policy(data)
            self._cache_policy(data)
        return data

    def cached_required(self) -> dict[str, Any] | None:
        """检查失败时仅信任之前验签且仍处于宽限期内的 REQUIRED 策略。"""
        path = self.cache_dir / "trusted-policy.json"
        try:
            cached = json.loads(path.read_text("utf-8")); data = cached["data"]
            self._verify_policy(data)
            if data.get("updatePolicy") != "REQUIRED": return None
            checked = float(cached["localCheckedAt"]); now = time.time()
            if now + 300 < checked: return data  # 本机时间明显回拨时从严处理
            expires = datetime.fromisoformat(data["policyExpiresAt"])
            grace = timedelta(hours=int(data.get("offlineGraceHours") or 0))
            if datetime.fromtimestamp(now) <= expires + grace: return data
        except Exception:
            return None
        return None

    def download_and_verify(self, decision: dict[str, Any], progress: Callable[[int, int], None] | None = None) -> Path:
        artifact = decision.get("artifact") or {}
        url, expected_size = artifact.get("downloadUrl"), int(artifact.get("fileSize") or 0)
        if not url or expected_size <= 0:
            raise UpdateError("服务端没有提供可安装构件")
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        target = self.cache_dir / f"artifact-{artifact['artifactId']}.zip"
        existing = target.stat().st_size if target.exists() else 0
        if existing > expected_size:
            target.unlink(); existing = 0
        headers = {"Range": f"bytes={existing}-"} if existing else {}
        self.report(decision, "DOWNLOAD_STARTED")
        try:
            with requests.get(url, headers=headers, stream=True, timeout=(10, 60)) as response:
                if existing and response.status_code != 206:
                    target.unlink(missing_ok=True); existing = 0
                    return self.download_and_verify(decision, progress)
                response.raise_for_status()
                with target.open("ab" if existing else "wb") as output:
                    done = existing
                    for chunk in response.iter_content(1024 * 1024):
                        if chunk:
                            output.write(chunk); done += len(chunk)
                            if progress: progress(done, expected_size)
        except requests.RequestException as exc:
            raise UpdateError(f"升级包下载失败，可稍后断点续传：{exc}") from exc
        if target.stat().st_size != expected_size:
            raise UpdateError("升级包大小不一致")
        self.report(decision, "DOWNLOAD_COMPLETED")
        hasher = hashlib.sha256()
        with target.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                hasher.update(chunk)
        digest = hasher.hexdigest()
        if digest != artifact.get("sha256"):
            self.report(decision, "VERIFY_FAILED", "SHA256_MISMATCH")
            target.unlink(missing_ok=True)
            raise UpdateError("升级包 SHA-256 校验失败，已删除损坏文件")
        canonical = "\n".join([
            "PDK-ARTIFACT-V1", str(decision["appId"]), str(decision["targetVersion"]),
            artifact["platform"], artifact["arch"], artifact["packageType"],
            str(artifact["fileSize"]), artifact["sha256"],
        ])
        self._verify(canonical, artifact.get("signature"), artifact.get("signingKeyId"), "artifact")
        self.report(decision, "VERIFY_SUCCEEDED")
        (self.cache_dir / "pending-update.json").write_text(json.dumps(decision, ensure_ascii=False, indent=2), encoding="utf-8")
        return target

    def launch_updater(self, decision: dict[str, Any], package: Path) -> None:
        artifact = decision["artifact"]
        install_root = Path(os.getenv("PDK_INSTALL_ROOT", Path(__file__).resolve().parent))
        public_key = self._key(artifact.get("signingKeyId"), "artifact")
        entry_point = str(self.config.get("entryPoint", "main.py"))
        native = self._find_native_updater()
        if native is not None:
            self._launch_native(decision, package, install_root, entry_point, public_key, native)
        else:
            self._launch_python(decision, package, install_root, entry_point, public_key)

    @staticmethod
    def _find_native_updater() -> Path | None:
        """优先使用 C++ 原生更新器（无 Python 依赖、体积小）。

        查找顺序刻意把 install_root 之外的位置放前面：更新器会整体替换
        install_root，若 exe 自身处于 install_root 内部会被一并覆盖。
        """
        explicit = os.getenv("PDK_NATIVE_UPDATER", "").strip()
        if explicit and Path(explicit).is_file():
            return Path(explicit)
        base = Path(__file__).resolve().parent
        candidates = [
            base.parent / "native_updater" / "build" / "Debug" / "pdk_updater.exe",  # 仓库构建输出（在 install_root 外）
            base.parent / "pdk_updater.exe",  # 与客户端平级的稳定位置
            base / "pdk_updater.exe",  # 兜底：同目录（注意会被更新覆盖，仅首轮可用）
        ]
        for candidate in candidates:
            if candidate.is_file():
                return candidate
        return None

    def _launch_native(self, decision: dict[str, Any], package: Path, install_root: Path,
                       entry_point: str, public_key: str, native_exe: Path) -> None:
        artifact = decision["artifact"]
        job = {
            "schemaVersion": 1,
            "packagePath": str(package.resolve()),
            "installRoot": str(install_root.resolve()),
            "targetVersion": decision["targetVersion"],
            "entryPoint": entry_point,
            "appId": int(decision["appId"]),
            "platform": artifact["platform"],
            "arch": artifact["arch"],
            "packageType": artifact["packageType"],
            "fileSize": int(artifact["fileSize"]),
            "sha256": artifact["sha256"],
            "signature": artifact["signature"],
            "publicKey": public_key,
            "parentPid": os.getpid(),
            "healthFile": str(self.cache_dir / "update-health.json"),
            "healthNonce": secrets.token_hex(24),
            "healthTimeoutSeconds": 60,
            "relaunchOnRollback": True,
            "telemetry": {
                "endpoint": self.client.base_url.rstrip("/") + "/api/v1/client/updates/events",
                "appId": int(decision["appId"]),
                "deviceId": self.device_id,
                "checkRequestId": decision["checkRequestId"],
                "eventToken": decision["eventToken"],
                "artifactId": artifact.get("artifactId"),
                "fromVersion": self.current_version,
                "targetVersion": decision["targetVersion"],
                "platform": artifact["platform"],
            },
        }
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        job_path = self.cache_dir / "update-job.json"
        job_path.write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")
        self.report(decision, "INSTALL_STARTED")
        env = os.environ.copy()
        env["PDK_UPDATER_DECISION_FILE"] = str(self.cache_dir / "pending-update.json")
        env["PDK_UPDATER_API_BASE"] = self.client.base_url
        env["PDK_UPDATER_DEVICE_ID"] = self.device_id
        env["PDK_PYTHON_EXE"] = sys.executable  # 供 C++ 启动器拉起 .py 客户端
        subprocess.Popen([str(native_exe), "--job", str(job_path)], env=env,
                         close_fds=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

    def _launch_python(self, decision: dict[str, Any], package: Path, install_root: Path,
                       entry_point: str, public_key: str) -> None:
        artifact = decision["artifact"]
        updater = Path(__file__).with_name("updater.py")
        args = [sys.executable, str(updater), "--package", str(package), "--install-root", str(install_root),
                "--version", decision["targetVersion"], "--entry-point", entry_point,
                "--app-id", str(decision["appId"]), "--platform", artifact["platform"], "--arch", artifact["arch"],
                "--package-type", artifact["packageType"], "--file-size", str(artifact["fileSize"]),
                "--sha256", artifact["sha256"], "--signature", artifact["signature"],
                "--public-key", public_key, "--parent-pid", str(os.getpid())]
        self.report(decision, "INSTALL_STARTED")
        env = os.environ.copy()
        env["PDK_UPDATER_DECISION_FILE"] = str(self.cache_dir / "pending-update.json")
        env["PDK_UPDATER_API_BASE"] = self.client.base_url
        env["PDK_UPDATER_DEVICE_ID"] = self.device_id
        subprocess.Popen(args, env=env, close_fds=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

    def report(self, decision: dict[str, Any], event: str, error: str | None = None) -> None:
        try:
            self.client.report_update_event({
                "checkRequestId": decision["checkRequestId"], "eventToken": decision["eventToken"],
                "artifactId": (decision.get("artifact") or {}).get("artifactId"), "eventType": event,
                "fromVersion": self.current_version, "targetVersion": decision.get("targetVersion"),
                "platform": "WINDOWS", "errorCategory": error,
            }, device_id=self.device_id)
        except Exception:
            pass

    def _verify_policy(self, data: dict[str, Any]) -> None:
        canonical = "\n".join([
            "PDK-POLICY-V1", str(data["protocolVersion"]), str(data["appId"]), data["channel"],
            data["platform"], data["arch"], str(data["policyRevision"]), data["updatePolicy"],
            str(data.get("minimumSupportedVersion") or ""), str(data.get("mandatoryReleaseId") or ""),
            str(data.get("targetVersion") or ""), data["policyIssuedAt"], data["policyExpiresAt"],
        ])
        self._verify(canonical, data.get("policySignature"), data.get("policySigningKeyId"), "policy")

    def _cache_policy(self, data: dict[str, Any]) -> None:
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        temp = self.cache_dir / "trusted-policy.json.tmp"
        temp.write_text(json.dumps({"localCheckedAt": time.time(), "data": data}, ensure_ascii=False), "utf-8")
        temp.replace(self.cache_dir / "trusted-policy.json")

    def _key(self, key_id: str | None, purpose: str) -> str:
        env = os.getenv("PDK_UPDATE_ARTIFACT_PUBLIC_KEY" if purpose == "artifact" else "PDK_UPDATE_POLICY_PUBLIC_KEY", "").strip()
        keys = self.config.get("artifactPublicKeys" if purpose == "artifact" else "policyPublicKeys", {})
        value = env or str(keys.get(key_id or "", "")).strip()
        if not value:
            raise UpdateError(f"未内置受信的 {purpose} 公钥：{key_id}")
        return value

    def _verify(self, canonical: str, signature: str | None, key_id: str | None, purpose: str) -> None:
        try:
            key = load_der_public_key(base64.b64decode(self._key(key_id, purpose)))
            if not isinstance(key, Ed25519PublicKey): raise ValueError("not Ed25519")
            key.verify(base64.b64decode(signature or ""), canonical.encode("utf-8"))
        except UpdateError: raise
        except Exception as exc: raise UpdateError(f"{purpose} Ed25519 签名校验失败") from exc
