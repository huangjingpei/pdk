"""生成升级系统两套独立 Ed25519 密钥，以及可直接加载的服务端 YAML。"""
from __future__ import annotations
import argparse
import base64
import json
import secrets
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, PublicFormat, NoEncryption

def pair() -> tuple[str, str]:
    private=Ed25519PrivateKey.generate()
    private_b64=base64.b64encode(private.private_bytes(Encoding.DER,PrivateFormat.PKCS8,NoEncryption())).decode()
    public_b64=base64.b64encode(private.public_key().public_bytes(Encoding.DER,PublicFormat.SubjectPublicKeyInfo)).decode()
    return private_b64, public_b64

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--output-dir",help="写入服务端私密 YAML 和客户端公钥 JSON 的目录")
    parser.add_argument("--public-base-url",default="http://127.0.0.1:8080")
    parser.add_argument("--storage-root",default="C:/pdk-data/client-updates")
    args=parser.parse_args()
    artifact_private,artifact_public=pair();policy_private,policy_public=pair()
    artifact_id="client-release-2026-01";policy_id="client-policy-2026-01"
    if not args.output_dir:
        print("# 请放入密钥管理系统/环境变量，禁止提交真实私钥")
        print(f"PDK_UPDATE_ARTIFACT_PRIVATE_KEY={artifact_private}")
        print(f"PDK_UPDATE_ARTIFACT_PUBLIC_KEY={artifact_public}")
        print(f"PDK_UPDATE_POLICY_PRIVATE_KEY={policy_private}")
        print(f"PDK_UPDATE_POLICY_PUBLIC_KEY={policy_public}")
        raise SystemExit(0)
    output=Path(args.output_dir).expanduser().resolve();output.mkdir(parents=True,exist_ok=True)
    yaml_path=output/"application-client-update.yml";public_path=output/"client-update-public-keys.json"
    yaml_path.write_text(f'''pdk:
  client-update:
    enabled: true
    storage-root: "{args.storage_root}"
    public-base-url: "{args.public_base_url}"
    download-token-secret: "{secrets.token_hex(32)}"
    event-token-secret: "{secrets.token_hex(32)}"
    rollout-hmac-secret: "{secrets.token_hex(32)}"
    rollout-key-version: "1"
    artifact-private-key: "{artifact_private}"
    artifact-key-id: "{artifact_id}"
    policy-private-key: "{policy_private}"
    policy-key-id: "{policy_id}"
    download-url-ttl-seconds: 600
    policy-ttl-hours: 24
''',encoding="utf-8")
    public_path.write_text(json.dumps({
        "artifactPublicKeys":{artifact_id:artifact_public},
        "policyPublicKeys":{policy_id:policy_public},
    },ensure_ascii=False,indent=2),encoding="utf-8")
    print(f"服务端私密配置: {yaml_path}")
    print(f"客户端公钥配置: {public_path}")
    print("不要把 application-client-update.yml 提交到 Git 或复制到客户端。")
