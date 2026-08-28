"""PDK 客户端核心 API 客户端（无 GUI 依赖）。

所有请求路径均对应后端真实接口，与 docs/TESTING_GUIDE.md 一致：
  - 客户端鉴权：/api/v1/client/auth/{register,login,sms/send,logout,unbind-device,change-password}
  - 卡密核销：/api/v1/card/activate （开放，无需登录）
  - 短效 Token 调度：/api/v1/dispatch/acquire-token、/api/v1/dispatch/report-result
  - 账号查询：/api/v1/client/account/{profile,usage,card}、/api/v1/client/resources/status

通信加密方案（必须与后端 AesByteFlipUtils 一致）：
  密文 = Base64( reverse( MAGIC('PD') + IV(12B) + AES-128-GCM(Token明文) ) )
  密钥  = SHA256( ROOT_SALT + "_" + epochMinutes//10 )[:16]
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import platform
import random
import string
import time
import uuid
from pathlib import Path
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT_SALT = os.getenv("PDK_SECURITY_ROOT_SALT", "PDK_SECRET_SALT_2026_ENTERPRISE")
DEFAULT_BASE_URL = os.getenv("PDK_API_BASE", "http://localhost:8080")
DEFAULT_APP_ID = int(os.getenv("PDK_APP_ID", "1"))


def load_client_config() -> dict[str, Any]:
    """读取生产构建配置；未指定时保持调试工作台可切换业务。"""
    configured = os.getenv("PDK_CLIENT_CONFIG", "").strip()
    if not configured:
        return {"appId": DEFAULT_APP_ID, "productionEditable": True, "displayName": "多业务调试版"}
    path = Path(configured).expanduser().resolve()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise RuntimeError(f"客户端构建配置读取失败: {path}: {exc}") from exc
    app_id = int(data.get("appId", 0))
    if app_id <= 0:
        raise RuntimeError(f"客户端构建配置 appId 非法: {path}")
    data["appId"] = app_id
    data["configPath"] = str(path)
    return data


class ApiError(RuntimeError):
    """网络层错误（区别于业务返回码）。"""


_SENSITIVE_KEYS = (
    "password", "newPassword", "oldPassword", "smsCode", "cardKey",
    "invitationCode", "paymentTxnNo", "tokenValue", "token", "publishUrl",
)


def redact_sensitive(payload: Any) -> Any:
    """调试日志脱敏：对密码 / token 类字段做掩码，避免明文泄漏。"""
    if isinstance(payload, dict):
        return {k: ("***" if k in _SENSITIVE_KEYS else redact_sensitive(v)) for k, v in payload.items()}
    if isinstance(payload, list):
        return [redact_sensitive(v) for v in payload]
    return payload


def _fingerprint_device_id() -> str:
    """兜底标识：基于本机指纹（主机名:MAC:操作系统）的确定性 ID。

    仅用于「首次无落盘记录」时作为持久化种子，以保证既有已绑定测试账号的设备 ID 不变。
    """
    source = f"{platform.node()}:{uuid.getnode()}:{platform.system()}"
    digest = hashlib.sha256(source.encode("utf-8")).hexdigest()[:24].upper()
    return f"PYQT-{digest}"


# 本地缓存文件：仅作为「首请求的引导缓存」，服务端 user.device_id 才是权威源。
_DEVICE_ID_DIR = os.path.join(os.path.expanduser("~"), ".pdk_client")
_DEVICE_ID_FILE = os.path.join(_DEVICE_ID_DIR, "device_id.json")


def load_device_id() -> str:
    """从本地缓存读取已持久化的 device_id（引导用，非权威）。读取失败返回空串。"""
    try:
        if os.path.exists(_DEVICE_ID_FILE):
            with open(_DEVICE_ID_FILE, "r", encoding="utf-8") as f:
                return (json.load(f).get("device_id") or "").strip()
    except Exception:
        pass
    return ""


def save_device_id(device_id: str) -> None:
    """将 device_id 写入本地缓存，使下次启动可携带正确请求头（避免鉴权重试死锁）。

    服务端仍是权威：登录/注册成功后会用服务端返回的 deviceId 覆盖此处。
    """
    if not device_id:
        return
    try:
        os.makedirs(_DEVICE_ID_DIR, exist_ok=True)
        with open(_DEVICE_ID_FILE, "w", encoding="utf-8") as f:
            json.dump(
                {"device_id": device_id, "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S")},
                f,
                ensure_ascii=False,
            )
    except Exception:
        pass  # 写盘失败不影响本次运行（退化为每次重算指纹）


def default_device_id() -> str:
    """返回本安装实例的设备标识（本地缓存优先，服务端权威兜底）。

    策略（方案A：服务端权威 + 本地缓存）：
      ① PDK_DEVICE_ID 环境变量强制指定（最高优先级，用于同机多实例互不冲突）；
      ② 本地缓存命中则直接复用（避免每次重算，并能在登录前携带正确请求头）；
      ③ 均无则以「本机指纹」为种子落地，兼容既有已绑定测试账号的设备 ID。
    真正权威的 device_id 来自服务端 user.device_id，登录/注册成功后会写回本地缓存。
    """
    env_id = (os.getenv("PDK_DEVICE_ID") or "").strip()
    if env_id:
        return env_id
    cached = load_device_id()
    if cached:
        return cached
    new_id = _fingerprint_device_id()
    save_device_id(new_id)
    return new_id


def decrypt_payload(payload: str) -> dict[str, Any]:
    """解密后端 acquire-token 下发的加密报文，返回明文 JSON dict。

    与后端 AesByteFlipUtils.encryptAndFlip 完全对称：先整体字节逆序，
    再校验魔数 'PD'，取 12 字节 IV 后用时间窗派生密钥做 AES-128-GCM 解密。
    """
    raw = base64.b64decode(payload)[::-1]
    if len(raw) < 14 or raw[:2] != b"PD":
        raise ValueError("不是有效的 PDK 加密报文（缺少 PD 魔数）")
    iv, ciphertext = raw[2:14], raw[14:]
    current_window = int(time.time() // 60 // 10)
    last_err: Optional[Exception] = None
    for window in (current_window, current_window - 1, current_window + 1):
        key = hashlib.sha256(f"{ROOT_SALT}_{window}".encode()).digest()[:16]
        try:
            plaintext = AESGCM(key).decrypt(iv, ciphertext, None)
            return json.loads(plaintext.decode("utf-8"))
        except Exception as exc:  # noqa: BLE001 - 尝试相邻时间窗
            last_err = exc
            continue
    raise ValueError(f"解密失败：时间窗口过期或数据损坏 ({last_err})")


@dataclass
class ClientSession:
    token_name: str = "satoken"
    token_value: str = ""
    phone: str = ""
    device_id: str = ""
    password: str = ""


class PdkApiClient:
    """对 PDK 后端发起真实 HTTP 调用的轻量客户端。"""

    def __init__(self, base_url: str = DEFAULT_BASE_URL, app_id: int = DEFAULT_APP_ID) -> None:
        if int(app_id) <= 0:
            raise ValueError("app_id 必须为正整数")
        self.base_url = base_url.rstrip("/")
        self._app_id = int(app_id)
        self.session = ClientSession()
        self.http = requests.Session()
        # 调试辅助：当前调用上下文的「期待」注解（由调用方设置，如场景 expected），
        # 用于客户端 HTTP 日志展示「期待什么」；on_request 钩子把每条请求回传给 GUI。
        self.expectation: str = ""
        self.on_request: Optional[Callable[[dict[str, Any]], None]] = None
        # 最后一条请求的完整记录（method/url/params/请求体/HTTP状态/响应体/期待），
        # 供 GUI 接口调试卡片展示 request/response 细节。
        self.last_request_record: Optional[dict[str, Any]] = None

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
        # 登录态归属具体业务；调试界面切换业务时不能复用原业务 Token。
        self.session.token_value = ""
        self.session.phone = ""
        self.session.password = ""

    # ------------------------------------------------------------------ 通用请求
    def request(
        self,
        method: str,
        path: str,
        *,
        authenticated: bool = False,
        include_phone: bool = True,
        include_device: bool = True,
        override_device_id: Optional[str] = None,
        headers: Optional[dict[str, str]] = None,
        json: Optional[dict[str, Any]] = None,
        params: Optional[dict[str, Any]] = None,
    ) -> dict[str, Any]:
        """发起请求并返回 CommonResult 完整报文（code/message/data）。

        业务失败（code != 200）也照常返回报文，便于边界测试断言返回码；
        仅在「网络不可达 / 非 JSON 响应」时返回 code=0 的本地错误报文。
        每次请求会经 on_request 钩子回传「请求+响应+期待」结构化记录，便于调试。
        """
        hdrs: dict[str, str] = {
            "Accept": "application/json",
            "X-PDK-App-ID": str(self.app_id),
        }
        if authenticated:
            if self.session.token_value:
                hdrs[self.session.token_name] = self.session.token_value
            if include_phone and self.session.phone:
                hdrs["X-PDK-Phone"] = self.session.phone
            dev = override_device_id if override_device_id is not None else self.session.device_id
            if include_device and dev:
                hdrs["X-PDK-Device-ID"] = dev
        if headers:
            hdrs.update(headers)
        full_url = f"{self.base_url}{path}"
        try:
            resp = self.http.request(
                method,
                full_url,
                headers=hdrs,
                json=json,
                params=params,
                timeout=20,
            )
            try:
                body = resp.json()
            except ValueError:
                body = {"code": 0, "message": f"服务端返回非 JSON（HTTP {resp.status_code}）", "data": None}
            http_status = resp.status_code
        except requests.RequestException as exc:
            body = {"code": 0, "message": f"网络请求失败: {exc}", "data": None}
            http_status = 0
        self._emit_request(method, full_url, params, json, http_status, body)
        return body

    def last_http_status(self) -> int:
        """返回最近一条请求的 HTTP 状态码（本地异常返回 0）。"""
        rec = self.last_request_record
        return int(rec.get("http_status", 0) or 0) if rec else 0

    def _emit_request(
        self,
        method: str,
        url: str,
        params: Optional[dict[str, Any]],
        request_json: Optional[dict[str, Any]],
        http_status: int,
        body: dict[str, Any],
    ) -> None:
        """保存最近请求记录并回传给 GUI 调试日志（钩子为空时仅保存）。"""
        rec = {
            "ts": time.strftime("%H:%M:%S"),
            "method": method,
            "url": url,
            "params": params,
            "request_json": redact_sensitive(request_json),
            "http_status": http_status,
            "code": int((body or {}).get("code", 0) or 0),
            "msg": str((body or {}).get("message", "")),
            "body": redact_sensitive(body),
            "expected": self.expectation if self.expectation else "",
        }
        self.last_request_record = rec
        if self.on_request is None:
            return
        try:
            self.on_request(rec)
        except Exception:  # noqa: BLE001 - 日志钩子异常不应影响主流程
            pass

    @staticmethod
    def is_ok(body: dict[str, Any]) -> bool:
        return body.get("code") == 200

    @staticmethod
    def code(body: dict[str, Any]) -> int:
        return int(body.get("code", 0) or 0)

    # ------------------------------------------------------------------ 鉴权相关
    def business_info(self) -> dict[str, Any]:
        """登录前读取当前构建 appId 的名称、描述、注册策略与可用状态。"""
        return self.request("GET", f"/api/v1/client/business/by-app/{self.app_id}")

    def send_sms(self, phone: str, purpose: str = "REGISTER") -> dict[str, Any]:
        return self.request("POST", "/api/v1/client/auth/sms/send",
                            json={"appId": self.app_id, "phone": phone, "purpose": purpose})

    def register(self, phone: str, password: str, device_id: str, sms_code: str,
                 invitation_code: str = "") -> dict[str, Any]:
        body = self.request("POST", "/api/v1/client/auth/register", json={
            "appId": self.app_id,
            "phone": phone,
            "password": password,
            "deviceId": device_id,
            "smsCode": sms_code,
            "invitationCode": invitation_code or None,
        })
        if self.is_ok(body):
            data = body.get("data") or {}
            self.session.token_name = data.get("tokenName", "satoken")
            self.session.token_value = data.get("tokenValue", "")
            self.session.phone = phone
            # 服务端权威：优先采用服务端返回的 deviceId，并写回本地缓存
            server_did = (data.get("deviceId") or device_id)
            self.session.device_id = server_did
            save_device_id(server_did)
            self.session.password = password
        return body

    def login(self, phone: str, password: str, device_id: str) -> dict[str, Any]:
        body = self.request("POST", "/api/v1/client/auth/login", json={
            "appId": self.app_id,
            "phone": phone,
            "password": password,
            "deviceId": device_id,
        })
        if self.is_ok(body):
            data = body.get("data") or {}
            self.session.token_name = data.get("tokenName", "satoken")
            self.session.token_value = data.get("tokenValue", "")
            self.session.phone = phone
            # 服务端权威：优先采用服务端返回的 deviceId，并写回本地缓存
            server_did = (data.get("deviceId") or device_id)
            self.session.device_id = server_did
            save_device_id(server_did)
            self.session.password = password
        return body

    def logout(self) -> dict[str, Any]:
        body = self.request("POST", "/api/v1/client/auth/logout", authenticated=True)
        if self.is_ok(body):
            self.session.token_value = ""
        return body

    def unbind_device(self) -> dict[str, Any]:
        body = self.request("POST", "/api/v1/client/auth/unbind-device", authenticated=True)
        if self.is_ok(body):
            # 方案A：device_id 为账号级稳定标识，解绑仅清登录态，保留 device_id
            # （与服务端不再置空 user.device_id 的语义一致），下次登录复用同一标识
            self.session.token_value = ""
        return body

    def change_password(self, phone: str, old_password: str, new_password: str) -> dict[str, Any]:
        return self.request("POST", "/api/v1/client/auth/change-password", json={
            "appId": self.app_id, "phone": phone,
            "oldPassword": old_password, "newPassword": new_password,
        })

    # ------------------------------------------------------------------ 卡密核销
    def activate_card(self, card_key: str, user_phone: str, device_id: str) -> dict[str, Any]:
        return self.request("POST", "/api/v1/card/activate", json={
            "appId": self.app_id,
            "cardKey": card_key.strip(),
            "userPhone": user_phone,
            "deviceId": device_id,
            "orderType": "NORMAL_SALE",
            "paymentChannel": "OFFLINE",
        })

    # ------------------------------------------------------------------ 调度网关
    def acquire_token(self, action_type: str, goods_id: str, *,
                      override_device_id: Optional[str] = None,
                      include_device: bool = True) -> dict[str, Any]:
        return self.request(
            "POST", "/api/v1/dispatch/acquire-token",
            authenticated=True, include_device=include_device,
            override_device_id=override_device_id,
            json={"actionType": action_type, "goodsId": goods_id,
                  "timestamp": int(time.time() * 1000)},
        )

    def report_result(self, lease_trace_id: str, status: str,
                      duration_ms: Optional[int] = None,
                      error_message: str = "") -> dict[str, Any]:
        return self.request(
            "POST", "/api/v1/dispatch/report-result",
            authenticated=True,
            json={"leaseTraceId": lease_trace_id, "status": status,
                  "responseDurationMs": duration_ms if duration_ms is not None else 1000,
                  "errorMessage": error_message},
        )

    # ------------------------------------------------------------------ 账号查询
    def profile(self) -> dict[str, Any]:
        return self.request("GET", "/api/v1/client/account/profile", authenticated=True)

    def usage(self) -> dict[str, Any]:
        return self.request("GET", "/api/v1/client/account/usage", authenticated=True)

    def resource_status(self) -> dict[str, Any]:
        return self.request("GET", "/api/v1/client/resources/status", authenticated=True)

    def card_list(self) -> dict[str, Any]:
        return self.request("GET", "/api/v1/client/account/card", authenticated=True)

    # ------------------------------------------------------------------ ZHIBO_LIVE 推流
    def create_live_publish_ticket(
        self,
        client_request_id: str = "",
        title: str = "客户端直播",
        protocol: str = "RTMP",
    ) -> dict[str, Any]:
        """登录 appId=3 后申请一次性短效 MediaMTX 推流地址。"""
        return self.request(
            "POST", "/api/v1/client/zhibo-live/publish-tickets",
            authenticated=True,
            json={
                "clientRequestId": client_request_id or str(uuid.uuid4()),
                "title": title,
                "requestedProtocol": protocol,
            },
        )

    def live_streams(self) -> dict[str, Any]:
        return self.request(
            "GET", "/api/v1/client/zhibo-live/streams/current", authenticated=True)

    def stop_live_stream(self, stream_session_no: str) -> dict[str, Any]:
        return self.request(
            "POST", f"/api/v1/client/zhibo-live/streams/{stream_session_no}/stop",
            authenticated=True,
        )


def random_phone() -> str:
    """生成一个未注册的测试手机号（1[3-9] 开头 + 9 位随机）。"""
    return "1" + random.choice("3456789") + "".join(random.choices(string.digits, k=9))


def random_password() -> str:
    """生成一个满足长度 >=8 的随机密码。"""
    return "Pdk" + "".join(random.choices(string.ascii_letters + string.digits, k=10))
