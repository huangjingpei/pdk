"""升级检查、可信策略缓存、断点下载与独立 updater 交接。"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Callable

import requests

from .api import UpdateApiClient
from .config import UpdateConfig
from .errors import UpdateError
from .security import artifact_canonical, policy_canonical, verify_ed25519

ProgressCallback = Callable[[int, int], None]


def _default_cache(app_id: int) -> Path:
    override = (os.getenv("PDK_UPDATE_CACHE") or "").strip()
    if override:
        return Path(override).expanduser()
    root = Path(os.getenv("LOCALAPPDATA") or Path.home())
    return root / "PDK" / str(app_id) / "updates"


class ClientUpdateManager:
    """与具体业务 UI 无关的升级领域服务。"""

    def __init__(self, config: UpdateConfig, device_id: str,
                 api: UpdateApiClient | None = None) -> None:
        if not device_id.strip():
            raise UpdateError("升级检查需要稳定的设备 ID")
        self.config = config
        self.api = api or UpdateApiClient(config, device_id)
        self.cache_dir = _default_cache(config.app_id)

    def close(self) -> None:
        self.api.close()

    def check(self) -> dict[str, Any]:
        data = self.api.check()
        self._validate_response_scope(data)
        if data.get("policySignature"):
            self._verify_policy(data)
            self._cache_policy(data)
        elif data.get("hasUpdate") or data.get("updatePolicy") == "REQUIRED":
            raise UpdateError("升级服务器返回了未签名的升级策略")
        if data.get("hasUpdate"):
            artifact = data.get("artifact") or {}
            if not artifact.get("signature") or not artifact.get("downloadUrl"):
                raise UpdateError("升级策略缺少已签名的安装构件")
            self.api.report(data, "OFFERED")
        return data

    def cached_required(self) -> dict[str, Any] | None:
        """网络失败时只信任验签通过且仍在离线宽限期内的强制策略。"""
        path = self.cache_dir / "trusted-policy.json"
        try:
            cached = json.loads(path.read_text(encoding="utf-8"))
            data = cached["data"]
            self._validate_response_scope(data)
            self._verify_policy(data)
            if data.get("updatePolicy") != "REQUIRED":
                return None
            checked_at = float(cached["localCheckedAt"])
            now = time.time()
            if now + 300 < checked_at:  # 本机时间明显回拨时从严处理
                return data
            expires_at = datetime.fromisoformat(str(data["policyExpiresAt"]))
            grace = timedelta(hours=int(data.get("offlineGraceHours") or 0))
            if datetime.fromtimestamp(now) <= expires_at + grace:
                return data
        except Exception:
            return None
        return None

    def download_and_verify(self, decision: dict[str, Any],
                            progress: ProgressCallback | None = None) -> tuple[Path, dict[str, Any]]:
        """下载并校验构件；下载令牌过期时自动重新检查并刷新一次。"""
        return self._download(decision, progress, allow_refresh=True)

    def _download(self, decision: dict[str, Any], progress: ProgressCallback | None,
                  allow_refresh: bool) -> tuple[Path, dict[str, Any]]:
        artifact = decision.get("artifact") or {}
        url = str(artifact.get("downloadUrl") or "")
        expected_size = int(artifact.get("fileSize") or 0)
        artifact_id = int(artifact.get("artifactId") or 0)
        if not url or expected_size <= 0 or artifact_id <= 0:
            raise UpdateError("服务端没有提供可安装构件")
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        target = self.cache_dir / f"artifact-{artifact_id}.zip"
        existing = target.stat().st_size if target.exists() else 0
        if existing > expected_size:
            target.unlink(missing_ok=True)
            existing = 0
        headers = {"Range": f"bytes={existing}-"} if existing else {}
        self.api.report(decision, "DOWNLOAD_STARTED")
        try:
            with requests.get(url, headers=headers, stream=True, timeout=(10, 60)) as response:
                if response.status_code in {401, 403} and allow_refresh:
                    refreshed = self.check()
                    new_artifact = refreshed.get("artifact") or {}
                    if int(new_artifact.get("artifactId") or 0) != artifact_id:
                        raise UpdateError("下载链接过期且服务端升级目标已经改变，请重新确认升级")
                    return self._download(refreshed, progress, allow_refresh=False)
                if existing and response.status_code != 206:
                    target.unlink(missing_ok=True)
                    existing = 0
                    if response.status_code == 200:
                        return self._download(decision, progress, allow_refresh=False)
                response.raise_for_status()
                done = existing
                with target.open("ab" if existing else "wb") as output:
                    for chunk in response.iter_content(1024 * 1024):
                        if chunk:
                            output.write(chunk)
                            done += len(chunk)
                            if progress:
                                progress(done, expected_size)
        except UpdateError:
            raise
        except requests.RequestException as exc:
            raise UpdateError(f"升级包下载失败，可稍后断点续传：{exc}") from exc

        if not target.is_file() or target.stat().st_size != expected_size:
            raise UpdateError("升级包大小不一致，已保留部分文件供下次断点续传")
        self.api.report(decision, "DOWNLOAD_COMPLETED")
        digest = _sha256(target)
        if digest.lower() != str(artifact.get("sha256") or "").lower():
            self.api.report(decision, "VERIFY_FAILED", "SHA256_MISMATCH")
            target.unlink(missing_ok=True)
            raise UpdateError("升级包 SHA-256 校验失败，损坏文件已删除")
        public_key = self.config.public_key("artifact", artifact.get("signingKeyId"))
        verify_ed25519(
            artifact_canonical(self.config.app_id, str(decision["targetVersion"]), artifact),
            artifact.get("signature"), public_key, "构件",
        )
        self.api.report(decision, "VERIFY_SUCCEEDED")
        self._write_json_atomic(self.cache_dir / "pending-update.json", decision)
        return target, decision

    def launch_updater(self, decision: dict[str, Any], package: Path) -> None:
        """启动安装器。调用方随后必须结束当前主进程。"""
        if not getattr(sys, "frozen", False):
            raise UpdateError("开发环境仅执行检查与下载；自动安装请使用 PyInstaller 发布版验证")
        artifact = decision["artifact"]
        updater_source = self._find_updater_binary()
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        updater_copy = self.cache_dir / f"pdk-updater-{self.config.updater_version}-{uuid.uuid4().hex[:8]}.exe"
        try:
            shutil.copy2(updater_source, updater_copy)
        except OSError as exc:
            raise UpdateError(f"无法准备独立升级器：{exc}") from exc

        install_root = Path(os.getenv("PDK_INSTALL_ROOT") or Path(sys.executable).resolve().parent).resolve()
        health_file = self.cache_dir / f"health-{uuid.uuid4().hex}.json"
        nonce = uuid.uuid4().hex
        health_file.unlink(missing_ok=True)
        public_key = self.config.public_key("artifact", artifact.get("signingKeyId"))
        args = [
            str(updater_copy), "--package", str(package), "--install-root", str(install_root),
            "--version", str(decision["targetVersion"]), "--entry-point", self.config.entry_point,
            "--app-id", str(self.config.app_id), "--platform", str(artifact["platform"]),
            "--arch", str(artifact["arch"]), "--package-type", str(artifact["packageType"]),
            "--file-size", str(artifact["fileSize"]), "--sha256", str(artifact["sha256"]),
            "--signature", str(artifact["signature"]), "--public-key", public_key,
            "--parent-pid", str(os.getpid()), "--health-file", str(health_file),
            "--health-nonce", nonce, "--health-timeout", str(self.config.health_timeout_seconds),
        ]
        env = os.environ.copy()
        env.update({
            "PDK_UPDATER_DECISION_FILE": str(self.cache_dir / "pending-update.json"),
            "PDK_UPDATER_API_BASE": self.config.server_base_url,
            "PDK_UPDATER_DEVICE_ID": self.api.device_id,
        })
        self.api.report(decision, "INSTALL_STARTED")
        try:
            subprocess.Popen(args, env=env, close_fds=True,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except OSError as exc:
            raise UpdateError(f"无法启动独立升级器：{exc}") from exc

    def _find_updater_binary(self) -> Path:
        candidates = [Path(sys.executable).resolve().parent / self.config.updater_executable]
        bundle = getattr(sys, "_MEIPASS", "")
        if bundle:
            candidates.append(Path(bundle) / self.config.updater_executable)
        for path in candidates:
            if path.is_file():
                return path
        raise UpdateError(f"发布目录缺少 {self.config.updater_executable}，请重新执行客户端构建脚本")

    def _validate_response_scope(self, data: dict[str, Any]) -> None:
        expected = (self.config.app_id, self.config.channel, self.config.platform, self.config.arch)
        actual = (int(data.get("appId") or 0), str(data.get("channel") or ""),
                  str(data.get("platform") or ""), str(data.get("arch") or ""))
        if actual != expected:
            raise UpdateError(f"升级策略目标不属于当前客户端：expected={expected}, actual={actual}")

    def _verify_policy(self, data: dict[str, Any]) -> None:
        key = self.config.public_key("policy", data.get("policySigningKeyId"))
        verify_ed25519(policy_canonical(data), data.get("policySignature"), key, "策略")

    def _cache_policy(self, data: dict[str, Any]) -> None:
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self._write_json_atomic(self.cache_dir / "trusted-policy.json", {
            "localCheckedAt": time.time(), "data": data,
        })

    @staticmethod
    def _write_json_atomic(path: Path, data: dict[str, Any]) -> None:
        temp = path.with_suffix(path.suffix + ".tmp")
        temp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(temp, path)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
