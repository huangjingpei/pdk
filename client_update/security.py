"""升级策略与构件的 Ed25519 验签。"""
from __future__ import annotations

import base64
from typing import Any

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import load_der_public_key

from .errors import UpdateError


def policy_canonical(data: dict[str, Any]) -> str:
    return "\n".join([
        "PDK-POLICY-V1", str(data["protocolVersion"]), str(data["appId"]),
        str(data["channel"]), str(data["platform"]), str(data["arch"]),
        str(data["policyRevision"]), str(data["updatePolicy"]),
        str(data.get("minimumSupportedVersion") or ""),
        str(data.get("mandatoryReleaseId") or ""), str(data.get("targetVersion") or ""),
        str(data["policyIssuedAt"]), str(data["policyExpiresAt"]),
    ])


def artifact_canonical(app_id: int, version: str, artifact: dict[str, Any]) -> str:
    return "\n".join([
        "PDK-ARTIFACT-V1", str(app_id), version, str(artifact["platform"]),
        str(artifact["arch"]), str(artifact["packageType"]),
        str(artifact["fileSize"]), str(artifact["sha256"]),
    ])


def verify_ed25519(canonical: str, signature: str | None, public_key: str, purpose: str) -> None:
    try:
        key = load_der_public_key(base64.b64decode(public_key, validate=True))
        if not isinstance(key, Ed25519PublicKey):
            raise ValueError("key is not Ed25519")
        key.verify(base64.b64decode(signature or "", validate=True), canonical.encode("utf-8"))
    except Exception as exc:
        raise UpdateError(f"{purpose} Ed25519 签名校验失败") from exc
