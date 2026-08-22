"""PDK 通信加密：AES-128-GCM + 动态时间窗 + 字节翻转（与后端 AesByteFlipUtils 对称）。

密文 = Base64( reverse( MAGIC('PD') + IV(12B) + AES-128-GCM(Token明文) ) )
密钥  = SHA256( ROOT_SALT + "_" + (epochSeconds // 60 // 10) )[:16]
"""
from __future__ import annotations

import base64
import hashlib
import time

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT_SALT = "PDK_SECRET_SALT_2026_ENTERPRISE"


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
    if raw[0] != 0x50 or raw[1] != 0x44:
        raise ValueError("魔数校验失败：非有效 PDK 加密报文")
    iv = raw[2:14]
    ct_with_tag = raw[14:]

    current_window = int(time.time() // 60 // 10)
    last_err: Exception | None = None
    for w in (current_window, current_window - 1, current_window + 1):
        try:
            key = derive_key(root_salt, w)
            plaintext = AESGCM(key).decrypt(iv, ct_with_tag, None)
            return plaintext.decode("utf-8")
        except Exception as exc:  # noqa: BLE001 - 尝试相邻时间窗
            last_err = exc
            continue
    raise ValueError(f"解密失败：时间窗口过期或数据损坏 ({last_err})")
