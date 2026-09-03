"""客户端升级构建配置加载与严格校验。"""
from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .errors import UpdateError

_VERSION = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _candidate_paths() -> list[Path]:
    paths: list[Path] = []
    configured = (os.getenv("PDK_UPDATE_CONFIG") or "").strip()
    if configured:
        paths.append(Path(configured).expanduser())
    if getattr(sys, "frozen", False):
        paths.append(Path(sys.executable).resolve().parent / "client-update.json")
        bundle = getattr(sys, "_MEIPASS", "")
        if bundle:
            paths.append(Path(bundle) / "client-update.json")
    paths.append(Path(__file__).resolve().parents[1] / "config" / "client-update.json")
    return paths


@dataclass(frozen=True)
class UpdateConfig:
    enabled: bool
    app_id: int
    biz_code: str
    display_name: str
    version: str
    channel: str
    updater_version: str
    protocol_version: int
    platform: str
    arch: str
    entry_point: str
    updater_executable: str
    server_base_url: str
    artifact_public_keys: dict[str, str]
    policy_public_keys: dict[str, str]
    health_timeout_seconds: int = 45

    @classmethod
    def load(cls, path: str | os.PathLike[str] | None = None) -> "UpdateConfig":
        source = Path(path).expanduser() if path else next((p for p in _candidate_paths() if p.is_file()), None)
        if source is None or not source.is_file():
            raise UpdateError("缺少 client-update.json，客户端升级配置不完整")
        try:
            raw: dict[str, Any] = json.loads(source.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            raise UpdateError(f"无法读取升级配置：{source}") from exc

        base_url = (os.getenv("PDK_UPDATE_BASE_URL") or os.getenv("PDK_BASE_URL") or
                    str(raw.get("serverBaseUrl") or "http://127.0.0.1:8080")).strip().rstrip("/")
        config = cls(
            enabled=_env_bool("PDK_UPDATE_ENABLED", bool(raw.get("enabled", True))),
            app_id=int(raw.get("appId") or 0),
            biz_code=str(raw.get("bizCode") or "").strip(),
            display_name=str(raw.get("displayName") or "客户端").strip(),
            version=str(raw.get("version") or "").strip(),
            channel=str(raw.get("channel") or "STABLE").strip().upper(),
            updater_version=str(raw.get("updaterVersion") or "").strip(),
            protocol_version=int(raw.get("protocolVersion") or 1),
            platform=str(raw.get("platform") or "WINDOWS").strip().upper(),
            arch=str(raw.get("arch") or "X64").strip().upper(),
            entry_point=str(raw.get("entryPoint") or "").strip().replace("\\", "/"),
            updater_executable=str(raw.get("updaterExecutable") or "pdk_updater.exe").strip().replace("\\", "/"),
            server_base_url=base_url,
            artifact_public_keys=dict(raw.get("artifactPublicKeys") or {}),
            policy_public_keys=dict(raw.get("policyPublicKeys") or {}),
            health_timeout_seconds=int(raw.get("healthTimeoutSeconds") or 45),
        )
        config.validate()
        return config

    def validate(self) -> None:
        if self.app_id <= 0:
            raise UpdateError("升级配置 appId 必须是正整数")
        if not _VERSION.fullmatch(self.version) or not _VERSION.fullmatch(self.updater_version):
            raise UpdateError("version 和 updaterVersion 必须使用 MAJOR.MINOR.PATCH 格式")
        if self.channel not in {"STABLE", "BETA"}:
            raise UpdateError("升级配置 channel 仅支持 STABLE/BETA")
        if self.platform != "WINDOWS" or self.arch != "X64":
            raise UpdateError("当前升级器仅支持 WINDOWS/X64")
        entry = Path(self.entry_point)
        if not self.entry_point or entry.is_absolute() or ".." in entry.parts:
            raise UpdateError("entryPoint 必须是安装目录内的安全相对路径")
        updater = Path(self.updater_executable)
        if (not self.updater_executable or updater.is_absolute() or ".." in updater.parts or
                len(updater.parts) != 1 or updater.suffix.lower() != ".exe"):
            raise UpdateError("updaterExecutable 必须是发布根目录内的 EXE 文件名")
        if not self.server_base_url.startswith(("http://", "https://")):
            raise UpdateError("serverBaseUrl 必须是完整的 HTTP(S) 地址")
        if not 10 <= self.health_timeout_seconds <= 300:
            raise UpdateError("healthTimeoutSeconds 必须在 10–300 秒之间")

    def public_key(self, purpose: str, key_id: str | None) -> str:
        env_name = ("PDK_UPDATE_ARTIFACT_PUBLIC_KEY" if purpose == "artifact"
                    else "PDK_UPDATE_POLICY_PUBLIC_KEY")
        env_value = (os.getenv(env_name) or "").strip()
        keys = self.artifact_public_keys if purpose == "artifact" else self.policy_public_keys
        value = env_value or str(keys.get(key_id or "", "")).strip()
        if not value:
            raise UpdateError(f"没有找到受信的 {purpose} 公钥：{key_id or '未提供 keyId'}")
        return value
