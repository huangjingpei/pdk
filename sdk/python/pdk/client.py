"""PDK 客户端 Python SDK —— 核心 API 客户端（无 GUI 依赖）。

与 client-pyqt/pdk_client.py 业务逻辑一致，但抽成独立可分发包，
并补充了「状态 / 事件 / 调试日志」三类回调，与 C++ / 易语言 SDK 模型统一。

依赖：requests、cryptography（pip install requests cryptography）
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import platform
import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Optional

import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .crypto import (decrypt_payload, derive_key, encrypt_envelope,
                     decrypt_response, fetch_public_config, fetch_public_config_pinned,
                     compute_public_key_fingerprint, is_envelope,
                     PublicKeyPinMismatchError, ROOT_SALT)
from .enums import Event, ResultCode, State

DEFAULT_BASE_URL = os.getenv("PDK_API_BASE", "http://localhost:8080")
DEFAULT_APP_ID = int(os.getenv("PDK_APP_ID", "1"))

# 向后兼容：保留 on_request 钩子（client-pyqt 调试面板用）
ApiError = RuntimeError
_SENSITIVE_KEYS = (
    "password", "newPassword", "oldPassword", "smsCode", "cardKey",
    "invitationCode", "paymentTxnNo", "tokenValue", "token",
)


def redact_sensitive(payload: Any) -> Any:
    """调试日志脱敏：密码 / token 类字段掩码。"""
    if isinstance(payload, dict):
        return {k: ("***" if k in _SENSITIVE_KEYS else redact_sensitive(v)) for k, v in payload.items()}
    if isinstance(payload, list):
        return [redact_sensitive(v) for v in payload]
    return payload


def _fingerprint_device_id() -> str:
    """兜底标识：基于本机指纹的确定性 ID（仅首次无落盘记录时作为种子）。"""
    source = f"{platform.node()}:{uuid.getnode()}:{platform.system()}"
    digest = hashlib.sha256(source.encode("utf-8")).hexdigest()[:24].upper()
    return f"PYQT-{digest}"


_DEVICE_ID_DIR = os.path.join(os.path.expanduser("~"), ".pdk_client")
_DEVICE_ID_FILE = os.path.join(_DEVICE_ID_DIR, "device_id.json")


def load_device_id() -> str:
    try:
        if os.path.exists(_DEVICE_ID_FILE):
            with open(_DEVICE_ID_FILE, "r", encoding="utf-8") as f:
                return (json.load(f).get("device_id") or "").strip()
    except Exception:
        pass
    return ""


def save_device_id(device_id: str) -> None:
    if not device_id:
        return
    try:
        os.makedirs(_DEVICE_ID_DIR, exist_ok=True)
        with open(_DEVICE_ID_FILE, "w", encoding="utf-8") as f:
            json.dump({"device_id": device_id, "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S")},
                      f, ensure_ascii=False)
    except Exception:
        pass


def default_device_id() -> str:
    env_id = (os.getenv("PDK_DEVICE_ID") or "").strip()
    if env_id:
        return env_id
    cached = load_device_id()
    if cached:
        return cached
    new_id = _fingerprint_device_id()
    save_device_id(new_id)
    return new_id


@dataclass
class ClientSession:
    token_name: str = "satoken"
    token_value: str = ""
    phone: str = ""
    device_id: str = ""
    password: str = ""


class PdkApiClient:
    """对 PDK 后端发起真实 HTTP 调用的轻量客户端。"""

    def __init__(self, base_url: str = DEFAULT_BASE_URL,
                 root_salt: str = ROOT_SALT,
                 device_id: str = "",
                 auto_envelope: bool = False,
                 public_key_pin: str = "",
                 app_id: int = DEFAULT_APP_ID) -> None:
        if int(app_id) <= 0:
            raise ValueError("app_id 必须为正整数")
        self.base_url = base_url.rstrip("/")
        self._app_id = int(app_id)
        self.root_salt = root_salt
        self.session = ClientSession()
        self.session.device_id = device_id or default_device_id()
        self.http = requests.Session()

        # 回调（与 C++ / 易语言 模型一致）
        self.on_state: Optional[Callable[[State, str], None]] = None
        self.on_event: Optional[Callable[[Event, str], None]] = None
        self.on_log: Optional[Callable[[str], None]] = None
        # 向后兼容：client-pyqt 调试面板用
        self.on_request: Optional[Callable[[dict], None]] = None
        self.expectation: str = ""
        self.last_request_record: Optional[dict] = None

        # 最近状态（便于无回调时轮询）
        self.last_state: State = State.Uninitialized
        self.last_state_detail: str = ""

        # 协议信封加密（RSA-OAEP + AES-256-GCM）：默认关闭，需拉取公钥后启用
        self.envelope_public_key: str = ""
        self.envelope_kid: str = "v1"
        # 跨请求会话密钥缓存：保存最近一次信封会话 AES 密钥，供后续 GET 等无 body
        # 响应解密（对应服务端「方案 A：会话级加密判定」）。
        self.envelope_session_key = None
        # P0 公钥指纹钉扎：非空时拉取公钥后强制比对指纹，不符拒绝启用
        self.public_key_pin: str = (public_key_pin or "").strip().lower()

        self._emit_state(State.Ready, f"客户端初始化完成，设备ID={self.session.device_id}")

        # 开启 auto_envelope 时主动拉取公钥；失败不影响明文链路
        if auto_envelope:
            try:
                self.refresh_crypto_config()
            except Exception:
                self._emit_log("⚠️ 自动拉取协议加密配置失败，将使用明文请求")

    @property
    def app_id(self) -> int:
        return self._app_id

    @app_id.setter
    def app_id(self, value: int) -> None:
        value = int(value)
        if value <= 0:
            raise ValueError("app_id 必须为正整数")
        if value == self._app_id:
            return
        self._app_id = value
        # 登录态归属具体业务；调试时切换业务必须清除旧业务会话。
        self.session.token_value = ""
        self.session.phone = ""
        self.session.password = ""
        self._emit_state(State.Ready, f"业务已切换，旧登录态已清除，appId={value}")

    # ---------------------------------------------------------------- 回调
    def _emit_state(self, s: State, detail: str) -> None:
        self.last_state = s
        self.last_state_detail = detail
        if self.on_state:
            try:
                self.on_state(s, detail)
            except Exception:
                pass

    def _emit_event(self, e: Event, msg: str) -> None:
        if self.on_event:
            try:
                self.on_event(e, msg)
            except Exception:
                pass

    def _emit_log(self, line: str) -> None:
        if self.on_log:
            try:
                self.on_log(line)
            except Exception:
                pass

    # ---------------------------------------------------------------- 通用请求
    def request(self, method, path, *, authenticated=False, include_phone=True,
                include_device=True, override_device_id=None,  headers=None,
                json_body=None, params=None, _retried: bool = False):
        hdrs: dict = {
            "Accept": "application/json",
            "X-PDK-App-ID": str(self.app_id),
        }
        if authenticated:
            if self.session.token_value:
                hdrs[self.session.token_name] = self.session.token_value
            if include_phone and self.session.phone:
                hdrs["X-PDK-Phone"] = self.session.phone
        # 协议加密需要稳定身份（设备ID）做跨请求会话映射，始终携带（非敏感）
        dev = override_device_id if override_device_id is not None else self.session.device_id
        if include_device and dev:
            hdrs.setdefault("X-PDK-Device-ID", dev)
        if headers:
            hdrs.update(headers)

        full_url = f"{self.base_url}{path}"

        # 协议信封加密：若已配置服务端公钥，用一次性 AES-256 密钥加密请求体
        session_key = None
        send_data = None
        if json_body is not None and self.envelope_public_key:
            envelope, session_key = encrypt_envelope(
                json.dumps(json_body, ensure_ascii=False),
                self.envelope_public_key, self.envelope_kid)
            # 缓存会话密钥，供后续 GET 等无 body 响应解密（方案 A）
            self.envelope_session_key = session_key
            hdrs["Content-Type"] = "application/json"
            send_data = envelope

        self._emit_event(Event.RequestSent, f"{method} {path}")
        safe_request = redact_sensitive(json_body)
        self._emit_log(f"▶ 请求: {method} {full_url}" + (f"\n   body: {safe_request}" if json_body else ""))
        if self.expectation:
            self._emit_log(f"🎯 期待: {self.expectation}")

        try:
            if send_data is not None:
                resp = self.http.request(method, full_url, headers=hdrs, data=send_data,
                                         params=params, timeout=20)
            else:
                resp = self.http.request(method, full_url, headers=hdrs, json=json_body,
                                         params=params, timeout=20)
            body_text = resp.text
            # 若持有会话密钥且响应是信封，则解密（本次请求加密 或 之前的会话仍有效均适用）
            if self.envelope_session_key is not None and is_envelope(body_text):
                try:
                    body_text = decrypt_response(body_text, self.envelope_session_key)
                except Exception:
                    self._emit_log("⚠️ 响应信封解密失败，退回原始响应")
            try:
                body = json.loads(body_text)
            except ValueError:
                body = {"code": 0, "message": f"服务端返回非 JSON（HTTP {resp.status_code}）", "data": None}
            http_status = resp.status_code
        except requests.RequestException as exc:
            body = {"code": 0, "message": f"网络请求失败: {exc}", "data": None}
            http_status = 0

        # 密钥版本不匹配(42901)/解密失败(42904)：自动重拉公钥并重试一次（防循环）
        if (not _retried) and body.get("code") in (42901, 42904) and self.envelope_public_key:
            try:
                self._emit_log("🔄 收到密钥错误，自动重拉公钥配置并重试")
                self.refresh_crypto_config()
                return self.request(method, path, authenticated=authenticated, include_phone=include_phone,
                                    include_device=include_device, override_device_id=override_device_id,
                                    headers=headers, json_body=json_body, params=params, _retried=True)
            except Exception as exc:
                self._emit_log(f"⚠️ 重拉公钥失败：{exc}")

        self._emit_event(Event.ResponseReceived,
                         f"HTTP {http_status} code={(body or {}).get('code')}")
        self._emit_log(f"◀ 响应: HTTP {http_status} | code={body.get('code')} | "
                       f"{body.get('message')} | data={body.get('data')}")
        self._emit_record(method, full_url, params, json_body, http_status, body)
        return body

    # ---------------------------------------------------------------- 公钥指纹钉扎 / TOFU
    def _pin_store_path(self) -> str:
        return os.path.join(os.path.expanduser("~"), ".pdk_client", "pin.json")

    def _load_pin_store(self) -> str:
        try:
            p = self._pin_store_path()
            if os.path.exists(p):
                with open(p, "r", encoding="utf-8") as f:
                    return (json.load(f).get("pin") or "").strip().lower()
        except Exception:
            pass
        return ""

    def _save_pin_store(self, fp: str) -> None:
        try:
            p = self._pin_store_path()
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w", encoding="utf-8") as f:
                json.dump({"pin": fp}, f)
        except Exception:
            pass

    def refresh_crypto_config(self) -> bool:
        """从服务端拉取协议加密配置（加密模式 / 公钥 / kid / 指纹）。

        成功后启用信封加密（灰度 optional：服务端接受明文与密文，客户端可渐进切换）。
        返回 True 表示已启用。

        公钥指纹钉扎（P0，防 MITM 替换公钥）按以下优先级校验：
        1. 显式 ``public_key_pin``（生产推荐：编译期内置 / 配置文件预置）；
        2. 本地钉扎存储文件（首次成功后写入，后续比对）；
        3. 首次且无任何预置指纹时采用 TOFU：信任并持久化当前指纹（弱于预置，仅防后续替换）。
        任一处指纹不符抛 :class:`PublicKeyPinMismatchError` 并拒绝启用。
        """
        try:
            cfg = fetch_public_config(self.base_url, app_id=self.app_id)
        except Exception as exc:
            self._emit_log(f"⚠️ 拉取公钥配置失败：{exc}")
            self._emit_state(State.Error, str(exc))
            raise
        data = cfg.get("data") or cfg
        pub = data.get("publicKey") or data.get("publicKey")

        # 公钥指纹钉扎 / TOFU
        if pub:
            actual = compute_public_key_fingerprint(pub)
            expected = self.public_key_pin          # 显式预置优先
            tofu = False
            if not expected:
                stored = self._load_pin_store()
                if stored:
                    expected = stored
                else:
                    tofu = True                       # 首次：信任并持久化
            if expected:
                if actual.lower() != expected.lower():
                    msg = (f"公钥指纹钉扎失败：期望 {expected}，实际 {actual}，"
                           f"疑似 MITM 替换公钥")
                    self._emit_log(f"🚨 {msg}")
                    self._emit_state(State.Error, msg)
                    raise PublicKeyPinMismatchError(msg)
                self._emit_log("🔒 公钥指纹钉扎校验通过")
            elif tofu:
                self._save_pin_store(actual)
                self._emit_log(f"🔒 首次信任并钉扎公钥指纹(TOFU)：{actual}")

        mode = data.get("encryptionMode") or data.get("encryptionMode")
        kid = data.get("kid") or data.get("kid") or "v1"
        if mode and mode != "off" and pub:
            self.envelope_public_key = pub
            self.envelope_kid = kid
            self._emit_log("🔐 已加载协议加密公钥，后续请求将自动加密")
            return True
        self._emit_log(f"服务端未启用协议加密（mode={mode}），继续使用明文")
        return False

    def _emit_record(self, method, url, params, req_json, http_status, body):
        rec = {
            "ts": time.strftime("%H:%M:%S"),
            "method": method, "url": url, "params": params,
            "request_json": redact_sensitive(req_json),
            "http_status": http_status,
            "code": int((body or {}).get("code", 0) or 0),
            "msg": str((body or {}).get("message", "")), "body": redact_sensitive(body),
            "expected": self.expectation if self.expectation else "",
        }
        self.last_request_record = rec
        if self.on_request:
            try:
                self.on_request(rec)
            except Exception:
                pass

    @staticmethod
    def is_ok(body) -> bool:
        return body.get("code") == 200

    # ---------------------------------------------------------------- 鉴权
    def business_info(self):
        """登录前读取当前 appId 的公开业务元数据和注册策略。"""
        return self.request("GET", f"/api/v1/client/business/by-app/{self.app_id}")

    def send_sms(self, phone, purpose="REGISTER"):
        self._emit_state(State.Ready, f"正在发送验证码到 {phone}")
        body = self.request("POST", "/api/v1/client/auth/sms/send",
                            json_body={"appId": self.app_id, "phone": phone, "purpose": purpose})
        if self.is_ok(body):
            self._emit_state(State.SmsSent, f"验证码已发送（{phone}）")
        elif body.get("code") == 42901:
            self._emit_state(State.Error, "短信过于频繁，请 60 秒后重试")
        return body

    def register(self, phone, password, sms_code, invitation_code=""):
        self._emit_state(State.Registering, f"正在注册 {phone}")
        body = self.request("POST", "/api/v1/client/auth/register", json_body={
            "appId": self.app_id, "phone": phone, "password": password,
            "deviceId": self.session.device_id, "smsCode": sms_code,
            "invitationCode": invitation_code or None,
        })
        if self.is_ok(body):
            self._apply_session(body, phone, password)
            self._emit_state(State.Registered, f"注册成功并登录：{phone}")
        else:
            self._emit_state(State.Error, body.get("message", ""))
        return body

    def login(self, phone, password):
        self._emit_state(State.LoggingIn, f"正在登录 {phone}")
        body = self.request("POST", "/api/v1/client/auth/login", json_body={
            "appId": self.app_id, "phone": phone, "password": password,
            "deviceId": self.session.device_id,
        })
        if self.is_ok(body):
            self._apply_session(body, phone, password)
            self._emit_state(State.LoggedIn, f"登录成功：{phone}")
        elif body.get("code") == ResultCode.DEVICE_KICK_OUT:
            self._emit_state(State.Kicked, body.get("message", ""))
        else:
            self._emit_state(State.Error, body.get("message", ""))
        return body

    def _apply_session(self, body, phone, password):
        data = body.get("data") or {}
        self.session.token_name = data.get("tokenName", "satoken")
        self.session.token_value = data.get("tokenValue", "")
        self.session.phone = phone
        server_did = data.get("deviceId") or self.session.device_id
        self.session.device_id = server_did
        save_device_id(server_did)  # 服务端权威，回写本地缓存
        self.session.password = password

    def logout(self):
        self._emit_state(State.LoggingOut, "正在注销")
        body = self.request("POST", "/api/v1/client/auth/logout", authenticated=True)
        if self.is_ok(body):
            self.session.token_value = ""
            self._emit_state(State.LoggedOut, "已注销")
        else:
            self._emit_state(State.Error, body.get("message", ""))
        return body

    def unbind_device(self):
        body = self.request("POST", "/api/v1/client/auth/unbind-device", authenticated=True)
        if self.is_ok(body):
            self.session.token_value = ""  # 方案A：保留 deviceId，仅清登录态
            self._emit_state(State.DeviceUnbound, "已解绑当前会话，账号设备标识保持不变")
        else:
            self._emit_state(State.Error, body.get("message", ""))
        return body

    def change_password(self, phone, old_password, new_password):
        body = self.request("POST", "/api/v1/client/auth/change-password", json_body={
            "appId": self.app_id, "phone": phone,
            "oldPassword": old_password, "newPassword": new_password,
        })
        if not self.is_ok(body):
            self._emit_state(State.Error, body.get("message", ""))
        return body

    def request_reset_sms(self, phone):
        """「忘记密码」链路：发送短信用途为 RESET_PASSWORD 的验证码。"""
        return self.send_sms(phone, "RESET_PASSWORD")

    def reset_password(self, phone, sms_code, new_password):
        """自助找回密码：用手机号 + 短信验证码重设密码（无需旧密码）。"""
        self._emit_state(State.Ready, f"正在通过短信验证码重置密码（{phone}）")
        body = self.request("POST", "/api/v1/client/auth/reset-password", json_body={
            "appId": self.app_id, "phone": phone,
            "smsCode": sms_code, "newPassword": new_password,
        })
        if self.is_ok(body):
            self._emit_state(State.Ready, "密码已重置，请用新密码登录")
        else:
            self._emit_state(State.Error, body.get("message", ""))
        return body

    # ---------------------------------------------------------------- 管理员密码治理（管理端调用）
    def admin_reset_password(self, user_id, new_password):
        """管理员代客户重置密码（强制下次登录改密）。需管理员会话。"""
        body = self.request("POST", f"/api/v1/admin/user/{user_id}/reset-password",
                            authenticated=True, json_body={"newPassword": new_password})
        if not self.is_ok(body):
            self._emit_state(State.Error, body.get("message", ""))
        return body

    def admin_set_password_policy(self, user_id, must_change: bool):
        """管理员切换客户「强制下次登录改密」标记。需管理员会话。"""
        body = self.request("PUT", f"/api/v1/admin/user/{user_id}/password-policy",
                            authenticated=True, json_body={"mustChange": bool(must_change)})
        if not self.is_ok(body):
            self._emit_state(State.Error, body.get("message", ""))
        return body

    # ---------------------------------------------------------------- 卡密
    def activate_card(self, card_key, user_phone, payment_channel="OFFLINE", actual_amount=0.0):
        payload: dict = {
            "appId": self.app_id,
            "cardKey": card_key.strip(),
            "userPhone": user_phone,
            "deviceId": self.session.device_id,
            "orderType": "NORMAL_SALE",
            "paymentChannel": payment_channel,
        }
        if actual_amount and actual_amount > 0:
            payload["actualAmount"] = actual_amount
        body = self.request("POST", "/api/v1/card/activate", json_body=payload)
        if not self.is_ok(body):
            self._emit_state(State.Error, body.get("message", ""))
        return body

    # ---------------------------------------------------------------- 调度
    def acquire_token(self, action_type, goods_id):
        self._emit_state(State.TokenAcquiring, f"正在申请短效 Token（{action_type}）")
        body = self.request("POST", "/api/v1/dispatch/acquire-token", authenticated=True, json_body={
            "actionType": action_type, "goodsId": goods_id,
            "timestamp": int(time.time() * 1000),
        })
        if self.is_ok(body):
            pass  # 仅拿到加密 VO，需 decrypt_token
        elif body.get("code") == ResultCode.NO_AVAILABLE_TOKEN:
            self._emit_event(Event.NoAvailableToken, body.get("message", ""))
            self._emit_state(State.TokenFailed, body.get("message", ""))
        elif body.get("code") == ResultCode.QUOTA_EXHAUSTED:
            self._emit_event(Event.QuotaExhausted, body.get("message", ""))
            self._emit_state(State.TokenFailed, body.get("message", ""))
        elif body.get("code") == ResultCode.SUBSCRIPTION_EXPIRED:
            self._emit_event(Event.SubscriptionExpired, body.get("message", ""))
            self._emit_state(State.TokenFailed, body.get("message", ""))
        else:
            self._emit_state(State.TokenFailed, body.get("message", ""))
        return body

    def acquire_token_decrypted(self, action_type, goods_id):
        """申请并解密短效 Token；成功返回 (api_body, plain_json)。"""
        body = self.acquire_token(action_type, goods_id)
        plain = ""
        if self.is_ok(body):
            try:
                data = body.get("data") or {}
                plain = decrypt_payload(data.get("encryptedPayload", ""), self.root_salt)
                self._emit_event(Event.DecryptSucceeded, "Token 解密成功")
                self._emit_state(State.TokenAcquired, "已取得并解密短效 Token，可立即向拼多多官方发包")
            except ValueError as exc:
                self._emit_event(Event.DecryptFailed, str(exc))
                self._emit_state(State.TokenFailed, str(exc))
        return body, plain

    def report_result(self, lease_trace_id, status, duration_ms=None, error_message=""):
        self._emit_state(State.ResultReporting, f"正在上报业务结果（{status}）")
        body = self.request("POST", "/api/v1/dispatch/report-result", authenticated=True, json_body={
            "leaseTraceId": lease_trace_id, "status": status,
            "responseDurationMs": duration_ms if duration_ms is not None else 1000,
            "errorMessage": error_message,
        })
        if not self.is_ok(body):
            self._emit_state(State.Error, body.get("message", ""))
        return body

    # ---------------------------------------------------------------- 查询
    def profile(self):
        return self.request("GET", "/api/v1/client/account/profile", authenticated=True)

    def usage(self, page=1, size=20):
        return self.request("GET", "/api/v1/client/account/usage", authenticated=True,
                            params={"page": page, "size": size})

    def resource_status(self):
        return self.request("GET", "/api/v1/client/resources/status", authenticated=True)

    def card_list(self):
        return self.request("GET", "/api/v1/client/account/card", authenticated=True)

    # ---------------------------------------------------------------- 便捷
    def is_logged_in(self) -> bool:
        return bool(self.session.token_value)

    def decrypt_token(self, encrypted_payload: str) -> str:
        """解密 acquire-token 返回的 encryptedPayload（透传 crypto.decrypt_payload）。"""
        return decrypt_payload(encrypted_payload, self.root_salt)
