#!/usr/bin/env python3
"""生成升级系统服务端密钥配置（含公钥，供后端启动自检配对）。

与 scripts/generate_update_keys.py 的区别：
  那份只写 private-key/key-id，缺 -public-key；而 ClientUpdateKeyInspector 在
  检测到 public-key 与 private-key 不配对时会【直接拒绝启动】。本脚本把公钥一并
  写入 YAML，保证一次生成即可启动，并额外输出客户端需要的公钥 JSON。

用法：
    python3 gen-update-keys.py --output /opt/pdk/config/client-update-keys.yml \
        --storage-root /opt/pdk/data/client-updates \
        --public-base-url http://121.43.150.109
"""
from __future__ import annotations

import argparse
import base64
import json
import secrets
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
    PublicFormat,
)


def gen_pair() -> tuple[str, str]:
    private = Ed25519PrivateKey.generate()
    priv_b64 = base64.b64encode(
        private.private_bytes(Encoding.DER, PrivateFormat.PKCS8, NoEncryption())
    ).decode()
    pub_b64 = base64.b64encode(
        private.public_key().public_bytes(
            Encoding.DER, PublicFormat.SubjectPublicKeyInfo
        )
    ).decode()
    return priv_b64, pub_b64


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", required=True, help="服务端私密 YAML 路径")
    ap.add_argument("--storage-root", required=True)
    ap.add_argument("--public-base-url", required=True)
    ap.add_argument(
        "--key-id-suffix",
        default="",
        help="key-id 后缀，便于区分多套环境（如 -prod）",
    )
    args = ap.parse_args()

    art_priv, art_pub = gen_pair()
    pol_priv, pol_pub = gen_pair()
    suffix = args.key_id_suffix
    art_id = f"client-release-2026-01{suffix}"
    pol_id = f"client-policy-2026-01{suffix}"

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)

    out.write_text(
        f'''# 由 gen-update-keys.py 自动生成 —— 含私钥，权限应为 600，严禁入库
pdk:
  client-update:
    enabled: true
    storage-root: "{args.storage_root}"
    public-base-url: "{args.public_base_url}"
    download-token-secret: "{secrets.token_hex(32)}"
    event-token-secret: "{secrets.token_hex(32)}"
    rollout-hmac-secret: "{secrets.token_hex(32)}"
    rollout-key-version: "1"
    artifact-private-key: "{art_priv}"
    artifact-key-id: "{art_id}"
    artifact-public-key: "{art_pub}"
    policy-private-key: "{pol_priv}"
    policy-key-id: "{pol_id}"
    policy-public-key: "{pol_pub}"
    download-url-ttl-seconds: 600
    policy-ttl-hours: 24
''',
        encoding="utf-8",
    )
    out.chmod(0o600)

    pub_path = out.parent / "client-update-public-keys.json"
    pub_path.write_text(
        json.dumps(
            {
                "artifactPublicKeys": {art_id: art_pub},
                "policyPublicKeys": {pol_id: pol_pub},
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    print(f"服务端私密配置: {out}")
    print(f"客户端公钥配置: {pub_path}")
    print(f"key-id: {art_id} / {pol_id}")
    print("不要把本文件提交到 Git；公钥需同步到客户端配置后才能升级。")


if __name__ == "__main__":
    main()
