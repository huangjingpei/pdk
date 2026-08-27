"""协议信封加密 端到端互通测试。

模拟「客户端加密请求 -> 服务端解密」「服务端加密响应 -> 客户端解密」全链路，
验证 Python 客户端与后端 com.pdk.security.BodyCryptoService 的格式完全一致。

运行：python examples/envelope_roundtrip_test.py
依赖：pip install cryptography
"""
import base64
import json
import os
import sys
import time
from typing import Tuple

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from pdk.crypto import encrypt_envelope, decrypt_response, is_envelope


def simulate_server_decrypt(envelope_json: str, private_key) -> Tuple[str, bytes]:
    """与后端 BodyCryptoService.decryptEnvelope 等价的服务端逻辑。"""
    env = json.loads(envelope_json)
    assert env["kid"] == "v1"
    wrapped = base64.b64decode(env["enc"])
    aes_key = private_key.decrypt(
        wrapped,
        padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
    )
    iv = base64.b64decode(env["iv"])
    data = base64.b64decode(env["data"])
    plain = AESGCM(aes_key).decrypt(iv, data, None).decode("utf-8")
    return plain, aes_key


def simulate_server_encrypt(plain_json: str, aes_key: bytes) -> str:
    """与后端 BodyCryptoService.encryptResponse 等价（enc 直接复用会话密钥）。"""
    iv = os.urandom(12)
    ct = AESGCM(aes_key).encrypt(iv, plain_json.encode("utf-8"), None)
    env = {
        "kid": "v1",
        "enc": base64.b64encode(aes_key).decode("ascii"),
        "iv": base64.b64encode(iv).decode("ascii"),
        "data": base64.b64encode(ct).decode("ascii"),
        "ts": int(time.time() * 1000),
        "rnd": base64.b64encode(os.urandom(8)).decode("ascii"),
    }
    return json.dumps(env, ensure_ascii=False)


def main() -> int:
    # 生成服务端 RSA-2048 密钥对
    priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pub_pem = priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode("utf-8")

    # 1) 客户端加密请求体
    req_plain = json.dumps(
        {"action": "login", "phone": "13800000000", "password": "secret"},
        ensure_ascii=False,
    )
    env, client_aes_key = encrypt_envelope(req_plain, pub_pem, "v1")
    assert is_envelope(env), "生成的信封未被 is_envelope 识别"

    # 2) 服务端解密请求
    req_decrypted, srv_aes_key = simulate_server_decrypt(env, priv)
    assert req_decrypted == req_plain, "请求解密结果与原文不一致"
    assert srv_aes_key == client_aes_key, "会话密钥在端到端不一致"
    print("[OK] 请求信封 -> 服务端解密成功，明文与密钥一致")

    # 3) 服务端加密响应（复用同一会话密钥）
    resp_plain = json.dumps(
        {"code": 200, "message": "ok", "data": {"token": "abc.def"}},
        ensure_ascii=False,
    )
    resp_env = simulate_server_encrypt(resp_plain, client_aes_key)

    # 4) 客户端解密响应
    resp_decrypted = decrypt_response(resp_env, client_aes_key)
    assert resp_decrypted == resp_plain, "响应解密结果与原文不一致"
    print("[OK] 响应信封 -> 客户端解密成功，明文一致")

    print("\n✅ 协议信封加密 客户端/服务端 双向互通验证通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
