"""PDK 通信加密。

本模块包含两套机制，互不冲突：

1. 旧方案（与后端 AesByteFlipUtils 对称）——用于「服务端 -> 客户端」下发的短效 Token：
   decrypt_payload / derive_key（AES-128-GCM + 动态时间窗 + 字节翻转）。

2. 新方案（协议级信封加密，本次新增）——用于「客户端 <-> 服务端」全量 body 保护：
   encrypt_envelope / decrypt_response / is_envelope / fetch_public_config
   客户端用【服务端公钥】RSA-OAEP 包装一次性 AES-256 密钥，服务端私钥解开后解密；服务端再用同一个
   会话密钥加密响应，客户端用同一密钥解密。请求侧仅做一次 RSA，响应侧零非对称开销。

对应后端：com.pdk.security.BodyCryptoService / SecurityKeyService。
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import time
from typing import Optional, Tuple

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT_SALT = "PDK_SECRET_SALT_2026_ENTERPRISE"

# 新方案常量（与后端 BodyCryptoService 严格对齐）
_ENVELOPE_AES_KEY_BYTES = 32      # AES-256
_ENVELOPE_IV_BYTES = 12           # GCM nonce


# ============================================================================
# 旧方案：acquire-token 下发的 encryptedPayload（保持向后兼容）
# ============================================================================
def derive_key(root_salt: str, window: int) -> bytes:
    """派生 16 字节（128-bit）AES 密钥。"""
    raw = f"{root_salt}_{window}".encode("utf-8")
    return hashlib.sha256(raw).digest()[:16]


def decrypt_payload(encrypted_payload: str, root_salt: str = ROOT_SALT) -> str:
    """解密 acquire-token 下发的 encryptedPayload，返回明文 JSON 字符串。

    自动容忍 ±1 个 10 分钟时间窗（应对时钟偏差）。失败抛 ValueError。
    """
    flipped = base64.b64decode(encrypted_payload)
    if len(flipped) < 14 + 16:
        raise ValueError("加密数据包长度不足")
    raw = flipped[::-1]  # 还原字节正序
    if raw[ 0] != 0x50 or raw[1] != 0x44:
        raise ValueError("魔数校验失败：非有效 PDK 加密报文")
    iv = raw[2:14]
    ct_with_tag = raw[14:]

    current_window = int(time.time() // 60 // 10)
    last_err: Optional[Exception] = None
    for w in (current_window, current_window - 1, current_window + 1):
        try:
            key = derive_key(root_salt, w)
            plaintext = AESGCM(key).decrypt(iv, ct_with_tag, None)
            return plaintext.decode("utf-8")
        except Exception as exc:  # noqa: BLE001 - 尝试相邻时间窗
            last_err = exc
            continue
    raise ValueError(f"解密失败：时间窗口过期或数据损坏 ({last_err})")


# ============================================================================
# 新方案：协议级信封加密（RSA-OAEP + AES-256-GCM）
# ============================================================================
def encrypt_envelope(plain_json: str, public_key_pem: str, kid: str = "v1") \
        -> Tuple[str, bytes]:
    """加密请求体为信封。

    返回 ``(envelope_json, session_aes_key)``。客户端需妥善保存 ``session_aes_key``，
    用于解密本次请求对应的服务端响应（服务端会用同一个会话密钥加密响应）。

    - 一次性随机 AES-256 密钥 -> RSA-OAEP（服务端公钥）包装进 ``enc``
    - body 用 AES-256-GCM 加密（密文 + 16 字节认证标签）进 ``data``
    - ``iv`` 为 12 字节 GCM nonce；``ts`` 为毫秒时间戳；``rnd`` 为随机串（防重放）
    """
    public_key = serialization.load_pem_public_key(public_key_pem.encode("utf-8"))
    aes_key = os.urandom(_ENVELOPE_AES_KEY_BYTES)
    iv = os.urandom(_ENVELOPE_IV_BYTES)

    ciphertext = AESGCM(aes_key).encrypt(iv, plain_json.encode("utf-8"), None)
    wrapped = public_key.encrypt(
        aes_key,
        padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
    )

    envelope = {
        "kid": kid,
        "enc": base64.b64encode(wrapped).decode("ascii"),
        "iv": base64.b64encode(iv).decode("ascii"),
        "data": base64.b64encode(ciphertext).decode("ascii"),
        "ts": int(time.time() * 1000),
        "rnd": base64.b64encode(os.urandom(8)).decode("ascii"),
    }
    return json.dumps(envelope, ensure_ascii=False), aes_key


def is_envelope(body: str) -> bool:
    """判断一段字符串是否为加密信封（用于区分明文响应）。"""
    if not body or not body.strip():
        return False
    try:
        node = json.loads(body)
        return (
            isinstance(node, dict)
            and "enc" in node
            and "data" in node
            and "iv" in node
            and "kid" in node
        )
    except Exception:
        return False


def decrypt_response(envelope_json: str, session_aes_key: bytes) -> str:
    """用请求会话密钥解密服务端返回的响应信封（服务端 encryptResponse 复用同一会话密钥）。"""
    env = json.loads(envelope_json)
    iv = base64.b64decode(env["iv"])
    data = base64.b64decode(env["data"])
    plaintext = AESGCM(session_aes_key).decrypt(iv, data, None)
    return plaintext.decode("utf-8")


def fetch_public_config(base_url: str, timeout: int = 10) -> dict:
    """拉取服务端公钥与加密模式（对应后端 /api/v1/client/config/public）。"""
    import requests  # 延迟导入，避免无网络环境下导入报错

    url = base_url.rstrip("/") + "/api/v1/client/config/public"
    resp = requests.get(url, timeout=timeout)
    return resp.json()


# ============================================================================
# 公钥指纹钉扎（P0：防 MITM 替换公钥）
# ============================================================================
def compute_public_key_fingerprint(public_key_pem: str) -> str:
    """计算公钥指纹 = SHA-256(公钥 DER 的 Base64 编码) 的 hex 前 32 字符。

    指纹与 PEM 文本里的换行/空白无关，只取决于公钥本身。
    客户端应通过独立可信渠道（编译期内置 / 配置文件 / 运维下发）预先持有期望指纹，
    拉取到公钥后用本函数计算并比对，不符则拒绝启用加密。
    """
    pub = serialization.load_pem_public_key(public_key_pem.encode("utf-8"))
    der = pub.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return hashlib.sha256(der).hexdigest()[:32]


class PublicKeyPinMismatchError(Exception):
    """公钥指纹钉扎校验失败：拉到的公钥与期望指纹不符，疑似 MITM 替换。"""


def fetch_public_config_pinned(base_url: str, expected_fingerprint: str,
                               timeout: int = 10) -> dict:
    """拉取公钥配置并做指纹钉扎校验。

    - ``expected_fingerprint`` 为空时退化为不校验（仅向后兼容，不推荐生产用）。
    - 指纹不符抛 :class:`PublicKeyPinMismatchError`，调用方应据此拒绝启用加密。
    """
    cfg = fetch_public_config(base_url, timeout=timeout)
    data = cfg.get("data") or cfg
    pub = data.get("publicKey") or ""
    if expected_fingerprint and pub:
        actual = compute_public_key_fingerprint(pub)
        if actual.lower() != expected_fingerprint.lower():
            raise PublicKeyPinMismatchError(
                f"公钥指纹钉扎失败：期望 {expected_fingerprint}，实际 {actual}，疑似 MITM 替换公钥"
            )
    return cfg
