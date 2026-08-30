"""
PDK 商业化平台 —— 客户端接入 SDK（Python 版）

覆盖场景（基于后端真实 API，见 docs/客户端接入方案.md）：
  - 登录 / 设备许可证激活（卡密绑定设备）
  - 修改密码 / 短信找回密码
  - 解绑设备 / 注销
  - 许可证与套餐查询
  - 不做心跳的「合法检查」：每次受保护请求由服务端 DeviceSecurityInterceptor 校验
    许可证有效性 / 设备绑定 / 手机号一致性；客户端通过 profile() 前置自查。
  - 可选传输加密：RSA-OAEP + AES-256-GCM 信封（默认 optional，可不开）。

依赖：
    pip install requests cryptography

用法：
    from pdk_client import PdkClient
    c = PdkClient("http://localhost:8080", app_id=2, phone="13454118763")
    c.login(password="xxxxxxxx", card_key="PDK-XXXX-XXXX-XXXX")   # 新设备：带卡密即激活
    print(c.profile())
"""

from __future__ import annotations

import base64
import json
import os
import random
import string
import time
import uuid
from typing import Any, Optional

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding as asym_padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

DEFAULT_TIMEOUT = 20
REPLAY_WINDOW_MS = 5 * 60 * 1000  # 与服务端 ±5 分钟防重放一致


class PdkClientError(Exception):
    """服务端返回的非 200 结果统一抛此异常。code 即后端 BusinessException 的业务码。"""

    def __init__(self, code: int, message: str, *, raw: Any = None):
        super().__init__(f"[{code}] {message}")
        self.code = code
        self.message = message
        self.raw = raw


class PdkClient:
    def __init__(
        self,
        base_url: str,
        app_id: int,
        phone: str,
        *,
        device_id: Optional[str] = None,
        use_crypto: bool = False,
        token: Optional[str] = None,
        token_name: str = "satoken",
    ):
        self.base_url = base_url.rstrip("/")
        self.app_id = int(app_id)
        self.phone = phone
        # 真实客户端应使用稳定的机器指纹（主板序列号 / MAC 哈希）。这里给一个基于主机的回退。
        self.device_id = device_id or self._stable_device_id()
        self.use_crypto = use_crypto
        self.token = token
        self.token_name = token_name
        # 加密相关（按需惰性拉取）
        self._server_pub_pem: Optional[str] = None
        self._kid: Optional[str] = None
        self._session_key: Optional[bytes] = None  # 最近一次信封会话密钥，用于解密响应

    # ------------------------------------------------------------------ 工具
    @staticmethod
    def _stable_device_id() -> str:
        mac = uuid.getnode()
        return "dev-" + uuid.UUID(int=mac).hex[:16]

    def _headers(self) -> dict:
        h = {
            "X-PDK-App-ID": str(self.app_id),
            "X-PDK-Phone": self.phone,
            "X-PDK-Device-ID": self.device_id,
            "Content-Type": "application/json",
        }
        if self.token:
            h[self.token_name] = self.token
        return h

    # ------------------------------------------------------------------ 底层请求
    def _request(self, method: str, path: str, json_body: Optional[dict] = None) -> Any:
        url = f"{self.base_url}{path}"
        body_str = json.dumps(json_body, ensure_ascii=False) if json_body is not None else None

        # 是否用信封加密请求体
        send_str: Optional[str] = None
        if self.use_crypto and body_str is not None:
            send_str = self._encrypt_body(body_str)

        headers = self._headers()
        if send_str is not None:
            # 信封本身是 JSON 字符串，直接作为 raw body 发送
            resp = requests.request(
                method, url, data=send_str, headers=headers, timeout=DEFAULT_TIMEOUT
            )
        else:
            resp = requests.request(
                method, url, json=json_body, headers=headers, timeout=DEFAULT_TIMEOUT
            )

        text = resp.text
        # 若响应也是信封（仅当本次/近期请求用过加密时），解密
        if self.use_crypto and self._is_envelope(text):
            payload = self._decrypt_body(text)
        else:
            try:
                payload = json.loads(text)
            except json.JSONDecodeError:
                raise PdkClientError(resp.status_code, f"非 JSON 响应: {text[:200]}", raw=text)

        if isinstance(payload, dict) and payload.get("code") == 200:
            return payload.get("data")
        if isinstance(payload, dict):
            raise PdkClientError(
                int(payload.get("code", resp.status_code)),
                payload.get("message", "未知错误"),
                raw=payload,
            )
        # 没有 code 字段（理论上不会），原样返回
        return payload

    # ------------------------------------------------------------------ 加密信封
    def fetch_public_config(self) -> dict:
        """拉取服务端加密参数（公钥 PEM / kid / 加密模式）。公开接口，无需登录。"""
        data = self._request("GET", "/api/v1/client/config/public")
        self._server_pub_pem = data.get("publicKey")
        self._kid = data.get("kid")
        return data

    def _load_pub_key(self):
        if not self._server_pub_pem:
            self.fetch_public_config()
        return serialization.load_pem_public_key(self._server_pub_pem.encode("utf-8"))

    def _encrypt_body(self, plain_str: str) -> str:
        pub = self._load_pub_key()
        aes_key = AESGCM.generate_key(bit_length=256)  # 32 字节
        iv = os.urandom(12)
        ct = AESGCM(aes_key).encrypt(iv, plain_str.encode("utf-8"), None)
        enc = pub.encrypt(
            aes_key,
            asym_padding.OAEP(
                mgf=asym_padding.MGF1(hashes.SHA256()),
                algorithm=hashes.SHA256(),
                label=None,
            ),
        )
        envelope = {
            "kid": self._kid,
            "enc": base64.b64encode(enc).decode("ascii"),
            "iv": base64.b64encode(iv).decode("ascii"),
            "data": base64.b64encode(ct).decode("ascii"),
            "ts": int(time.time() * 1000),
            "rnd": "".join(random.choices(string.ascii_letters + string.digits, k=16)),
        }
        self._session_key = aes_key  # 服务端会用同一把密钥加密响应
        return json.dumps(envelope, ensure_ascii=False)

    @staticmethod
    def _is_envelope(text: str) -> bool:
        try:
            o = json.loads(text)
        except Exception:
            return False
        return isinstance(o, dict) and {"enc", "data", "iv", "kid"}.issubset(o.keys())

    def _decrypt_body(self, text: str) -> Any:
        o = json.loads(text)
        iv = base64.b64decode(o["iv"])
        data = base64.b64decode(o["data"])
        if self._session_key is None:
            raise PdkClientError(42904, "收到加密响应但本地无会话密钥，请用加密请求建立会话")
        pt = AESGCM(self._session_key).decrypt(iv, data, None)
        return json.loads(pt.decode("utf-8"))

    # ------------------------------------------------------------------ 业务接口
    def business_info(self) -> dict:
        """登录前可读取的业务安全元数据（无需鉴权）。"""
        return self._request("GET", f"/api/v1/client/business/by-app/{self.app_id}")

    def login(self, password: str, *, card_key: Optional[str] = None,
              device_name: str = "", platform: str = "python", client_version: str = "1.0.0") -> dict:
        """
        登录。
        - DEVICE_LICENSE 业务：若本设备尚未绑定许可证，必须传 card_key（卡密即激活设备）；
          已绑定设备再次登录可省略 card_key。
        - 成功后自动保存 token（tokenName / tokenValue）与许可证信息。
        """
        body = {
            "appId": self.app_id,
            "phone": self.phone,
            "password": password,
            "deviceId": self.device_id,
            "deviceName": device_name,
            "platform": platform,
            "clientVersion": client_version,
        }
        if card_key:
            body["cardKey"] = card_key
        data = self._request("POST", "/api/v1/client/auth/login", body)
        # 保存会话令牌（DEVICE_LICENSE 下 token 主体是 license:<id>）
        if data.get("tokenName") and data.get("tokenValue"):
            self.token_name = data["tokenName"]
            self.token = data["tokenValue"]
        return data

    def activate_device(self, password: str, card_key: str, **kw) -> dict:
        """新设备激活 = 带卡密登录（即把卡密绑定到新机器）。"""
        return self.login(password, card_key=card_key, **kw)

    def logout(self) -> str:
        return self._request("POST", "/api/v1/client/auth/logout")

    def change_password(self, old_password: str, new_password: str) -> str:
        body = {
            "appId": self.app_id,
            "phone": self.phone,
            "oldPassword": old_password,
            "newPassword": new_password,
        }
        return self._request("POST", "/api/v1/client/auth/change-password", body)

    def send_sms(self, purpose: str = "RESET_PASSWORD") -> dict:
        """发送短信验证码。purpose ∈ {REGISTER, RESET_PASSWORD}。"""
        body = {"appId": self.app_id, "phone": self.phone, "purpose": purpose}
        return self._request("POST", "/api/v1/client/auth/sms/send", body)

    def reset_password(self, sms_code: str, new_password: str) -> str:
        """短信验证码找回密码（不需要旧密码）。成功后旧会话会被踢。"""
        body = {
            "appId": self.app_id,
            "phone": self.phone,
            "smsCode": sms_code,
            "newPassword": new_password,
        }
        return self._request("POST", "/api/v1/client/auth/reset-password", body)

    def unbind_device(self) -> str:
        """解绑当前设备。DEVICE_LICENSE：解绑许可证设备（有效期继续计算）；
        账号订阅：解绑用户设备，可在新电脑重登。"""
        return self._request("POST", "/api/v1/client/auth/unbind-device")

    # ----- 许可证 / 账户查询（受保护，需登录） -----
    def profile(self) -> dict:
        """账户与许可证快照。每次返回 expireTime / status，是「无心跳合法检查」的自查入口。"""
        return self._request("GET", "/api/v1/client/account/profile")

    def usage(self, page: int = 1, size: int = 20) -> dict:
        return self._request("GET", f"/api/v1/client/account/usage?page={page}&size={size}")

    def account_card(self) -> dict:
        return self._request("GET", "/api/v1/client/account/card")

    def device_license_current(self) -> dict:
        return self._request("GET", "/api/v1/client/device-license/current")

    def device_license_list(self) -> list:
        return self._request("GET", "/api/v1/client/device-license/devices")

    def device_license_renewal_history(self) -> list:
        return self._request("GET", "/api/v1/client/device-license/renewal-history")

    def device_license_unbind(self) -> str:
        return self._request("POST", "/api/v1/client/device-license/unbind")

    # ----- 直播业务（ZHIBO_LIVE）专属 -----
    def live_publish_ticket(self, title: str = "", **kw) -> dict:
        body = {"title": title, **kw}
        return self._request("POST", "/api/v1/client/zhibo-live/publish-tickets", body)

    def live_streams_current(self) -> list:
        return self._request("GET", "/api/v1/client/zhibo-live/streams/current")

    def live_stream_stop(self, session_no: str) -> str:
        return self._request("POST", f"/api/v1/client/zhibo-live/streams/{session_no}/stop")

    # ------------------------------------------------------------------ 合法检查
    def verify_session(self) -> dict:
        """
        无心跳合法检查（客户端侧）。调用 profile() 解析当前许可证 / 账号状态，
        返回结构化结论，便于在每次业务操作前主动判断，而不是等业务报错才发现。

        返回示例：
            {"ok": True, "mode": "DEVICE_LICENSE",
             "license_status": "ACTIVE", "expire_at": "2026-09-30T...", "expired": False}
        """
        prof = self.profile()
        result: dict = {"ok": True, "raw": prof}
        lic = prof.get("deviceLicense")
        result["mode"] = prof.get("authorizationMode")
        if lic:
            expire = lic.get("expireAt") or lic.get("expire_time")
            result["license_status"] = lic.get("status")
            result["expire_at"] = expire
            result["expired"] = bool(expire) and self._is_past(expire)
        else:
            expire = prof.get("expireTime") or prof.get("expire_time")
            result["expire_at"] = expire
            result["expired"] = bool(expire) and self._is_past(expire)
        return result

    @staticmethod
    def _is_past(expire_str: str) -> bool:
        try:
            from datetime import datetime
            dt = datetime.fromisoformat(expire_str.replace("Z", "+00:00"))
            return dt.timestamp() < time.time()
        except Exception:
            return False


if __name__ == "__main__":
    # 最小可运行示例：ZHIBO_AI 客户端登录并自查
    client = PdkClient("http://localhost:8080", app_id=2, phone="13454118763")
    print("业务信息:", client.business_info())
    # 新设备首次登录需要卡密（激活设备）；已绑定的设备可只传密码
    res = client.login(password="YourPass123", card_key="PDK-XXXX-XXXX-XXXX")
    print("登录成功, token 头部:", res["tokenName"])
    print("许可证:", res.get("deviceLicense"))
    print("合法检查:", client.verify_session())
