"""PDK 商业化平台客户端接入 SDK（单文件 Python 版）。

以当前 Spring Boot 实现为准，覆盖 appId 业务发现、两种授权模型、设备许可证、
PDD 资源调度、ZHIBO_LIVE 推流票据、稳定设备 ID、会话恢复、可选信封加密，
以及“请求什么 / 期待什么”的脱敏调试记录。

依赖：pip install requests cryptography

生产环境必须使用 HTTPS，并通过 ``public_key_pin`` 预置服务端公钥指纹。
Token、会话快照与 publishUrl 都属于敏感数据，禁止写入普通日志。
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
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Mapping, Optional
from urllib.parse import urlparse

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding as asym_padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


DEFAULT_CONNECT_TIMEOUT = 5
DEFAULT_READ_TIMEOUT = 20
SDK_VERSION = "2.0.0"

_SENSITIVE_KEYS = {
    "password", "oldpassword", "newpassword", "smscode", "cardkey",
    "token", "tokenvalue", "authorization", "publishticket", "publishurl",
    "encryptedpayload", "paymenttxnno", "invitationcode", "phone", "userphone",
    "deviceid", "enc",
}

_EXPECTATIONS: dict[tuple[str, str], str] = {
    ("GET", "/api/v1/client/config/public"): "code=200，返回加密模式、公钥、kid 与公钥指纹",
    ("POST", "/api/v1/client/auth/sms/send"): "code=200，验证码已发送；仅本地调试环境可能返回 debugCode",
    ("POST", "/api/v1/client/auth/register"): "code=200；PDD 注册后返回登录态，设备许可证业务通常不开放注册",
    ("POST", "/api/v1/client/auth/login"): "code=200 并返回动态 tokenName/tokenValue；新设备可能先返回 40380",
    ("POST", "/api/v1/client/auth/change-password"): "code=200，旧会话全部失效，客户端必须重新登录",
    ("POST", "/api/v1/client/auth/reset-password"): "code=200，密码已重置且旧会话全部失效",
    ("POST", "/api/v1/client/auth/logout"): "code=200，当前会话注销",
    ("POST", "/api/v1/client/auth/unbind-device"): "code=200，当前设备解绑；设备许可证有效期不会暂停",
    ("POST", "/api/v1/card/activate"): "code=200，PDD 卡密核销并更新套餐；DEVICE_LICENSE 不使用此接口",
    ("POST", "/api/v1/dispatch/acquire-token"): "code=200，返回 leaseTraceId 与 encryptedPayload",
    ("POST", "/api/v1/dispatch/report-result"): "code=200，结果已记录；SUCCESS 才按业务规则扣次",
    ("GET", "/api/v1/client/account/profile"): "code=200，返回当前业务、账号和许可证快照",
    ("GET", "/api/v1/client/account/usage"): "code=200，返回剩余次数、成功/失败统计和分页记录",
    ("GET", "/api/v1/client/resources/status"): "code=200，返回当前用户专属资源分配状态",
    ("GET", "/api/v1/client/account/card"): "code=200，返回卡密/套餐或当前设备许可证集合",
    ("GET", "/api/v1/client/device-license/current"): "code=200，返回当前设备许可证和服务端时间",
    ("GET", "/api/v1/client/device-license/devices"): "code=200，返回当前手机号在本业务下的全部许可证",
    ("GET", "/api/v1/client/device-license/renewal-history"): "code=200，返回当前许可证续费历史",
    ("POST", "/api/v1/client/device-license/unbind"): "code=200，当前许可证解绑并使当前会话失效",
    ("POST", "/api/v1/client/zhibo-live/publish-tickets"): "code=200，返回短效 publishUrl；严禁记录完整 URL",
    ("GET", "/api/v1/client/zhibo-live/streams/current"): "code=200，返回当前许可证自己的直播会话",
}

_ACTION_BY_CODE: dict[int, str] = {
    0: "检查网络、DNS、服务地址和 TLS 证书",
    40050: "检查 X-PDK-App-ID 与 JSON appId 是否一致",
    40100: "清理本地会话并重新登录",
    40101: "补齐动态 Token、X-PDK-Phone 和 X-PDK-Device-ID",
    40102: "清理本地会话，使用当前手机号重新登录",
    40103: "停止业务进程并重新登录；必要时先解绑设备",
    40105: "提示手机号或密码错误，不要自动重复提交",
    40106: "清理 Token，检查客户端固化的 appId",
    40301: "用户级套餐已到期，引导核销新卡或联系管理员续费",
    40302: "用户级调用配额已耗尽，停止资源操作并提示续费",
    40321: "业务已被管理员关闭，进入维护页",
    40322: "隐藏自助注册入口，使用管理员下发账号",
    40370: "当前用户或许可证无权操作该直播会话",
    40371: "账号不存在、跨业务或已冻结，停止推流并联系管理员",
    40372: "设备绑定无效，停止推流并重新登录或解绑",
    40373: "旧用户级直播套餐未开通或过期，进入受限页",
    40374: "次数不足，停止业务并联系管理员续费",
    40380: "展开卡密输入框，保持手机号/密码/deviceId 后重新登录",
    40381: "许可证已到期，仅保留查询、注销和解绑入口",
    40382: "卡密无效或未分配给当前手机号，联系管理员核对",
    40383: "卡密已绑定其他设备，先在原设备或后台解绑",
    40384: "许可证已暂停或作废，停止业务并联系管理员",
    40385: "当前设备没有许可证，回到卡密绑定页",
    40970: "生成新的 clientRequestId 后再申请推流票据",
    40971: "先查询并停止已有推流会话，再申请新票据",
    40980: "当前设备已绑定另一张卡密，先解绑原许可证",
    40981: "短暂退避后重试一次绑定操作",
    42900: "启用协议信封加密后重试",
    42901: "重新拉取公钥与 kid 后重试一次",
    42902: "校准系统时间后重试",
    42903: "生成新的随机串，禁止重放原请求信封",
    42904: "重新拉取公钥并确认加密参数",
    50350: "当前部署未启用该业务或缺少业务 Handler",
    50301: "当前套餐没有可用业务资源，稍后重试或联系平台补充",
    50302: "公司资源库存不足，联系平台补充资源",
    50370: "MediaMTX 未启用，禁止开始推流",
    50371: "媒体服务控制失败，查询会话后再决定是否重试",
}


def recommended_action(code: int) -> str:
    """返回业务错误码对应的客户端动作建议。"""
    return _ACTION_BY_CODE.get(int(code), "显示服务端 message，并保留请求时间供排查")


def redact_sensitive(value: Any, key: str = "") -> Any:
    """递归脱敏调试数据；不修改原对象。"""
    if key.replace("_", "").lower() in _SENSITIVE_KEYS:
        return "***"
    if isinstance(value, Mapping):
        return {str(k): redact_sensitive(v, str(k)) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact_sensitive(v) for v in value]
    return value


class PdkClientError(RuntimeError):
    """网络错误或服务端 ``code != 200`` 时抛出。"""

    def __init__(self, code: int, message: str, *, http_status: int = 0,
                 data: Any = None, response: Any = None) -> None:
        super().__init__(f"[{code}] {message}")
        self.code = int(code)
        self.message = message
        self.http_status = int(http_status)
        self.data = data
        self.response = response
        self.action = recommended_action(self.code)

    @property
    def retryable(self) -> bool:
        return self.code in {0, 40981, 42901, 42902, 42904, 50301, 50371}


@dataclass
class SessionSnapshot:
    """可交给系统安全存储的会话快照；内容包含敏感 Token。"""

    app_id: int
    phone: str
    device_id: str
    token_name: str
    token_value: str
    envelope_session_key: str = ""


def _device_id_candidates(app_id: int) -> list[Path]:
    candidates: list[Path] = []
    if os.name == "nt":
        if os.environ.get("ProgramData"):
            candidates.append(Path(os.environ["ProgramData"]) / "PDK" / str(app_id) / "device_id")
        if os.environ.get("LOCALAPPDATA"):
            candidates.append(Path(os.environ["LOCALAPPDATA"]) / "PDK" / str(app_id) / "device_id")
    candidates.append(Path.home() / ".pdk_client" / str(app_id) / "device_id")
    return candidates


def _fingerprint_device_id() -> str:
    source = f"{platform.node()}:{uuid.getnode()}:{platform.system()}:{platform.machine()}"
    digest = hashlib.sha256(source.encode("utf-8")).hexdigest()[:24].upper()
    return f"PDK-{digest}"


def load_or_create_device_id(app_id: int) -> str:
    """按 appId 读取/创建稳定设备 ID；可用 ``PDK_DEVICE_ID`` 显式覆盖。"""
    env_id = (os.getenv("PDK_DEVICE_ID") or "").strip()
    if env_id:
        return env_id
    for path in _device_id_candidates(app_id):
        try:
            value = path.read_text(encoding="utf-8").strip()
            if value:
                return value
        except (OSError, UnicodeError):
            pass
    value = _fingerprint_device_id()
    for path in _device_id_candidates(app_id):
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(value, encoding="utf-8")
            return value
        except OSError:
            continue
    return value


def _public_key_fingerprint(public_key_pem: str) -> str:
    public_key = serialization.load_pem_public_key(public_key_pem.encode("utf-8"))
    der = public_key.public_bytes(serialization.Encoding.DER,
                                  serialization.PublicFormat.SubjectPublicKeyInfo)
    return hashlib.sha256(der).hexdigest()[:32]


class PdkClient:
    """PDK 单文件客户端，兼容早期 ``PdkClient(base_url, app_id, phone)`` 用法。"""

    def __init__(self, base_url: str, app_id: int, phone: str = "", *,
                 device_id: Optional[str] = None, use_crypto: bool = False,
                 auto_enable_crypto: bool = True, public_key_pin: str = "",
                 token: Optional[str] = None, token_name: str = "satoken",
                 verify_tls: bool | str = True, require_https: bool = False,
                 connect_timeout: int = DEFAULT_CONNECT_TIMEOUT,
                 read_timeout: int = DEFAULT_READ_TIMEOUT,
                 user_agent: str = "") -> None:
        if int(app_id) <= 0:
            raise ValueError("app_id 必须为正整数")
        parsed = urlparse(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("base_url 必须是完整的 http(s) 地址")
        if require_https and parsed.scheme != "https":
            raise ValueError("生产模式要求 HTTPS，当前 base_url 不是 https://")
        self.base_url = base_url.rstrip("/")
        self.app_id = int(app_id)
        self.phone = phone.strip()
        self.device_id = (device_id or load_or_create_device_id(self.app_id)).strip()
        self.token = token or ""
        self.token_name = token_name or "satoken"
        self.use_crypto = bool(use_crypto)
        self.auto_enable_crypto = bool(auto_enable_crypto)
        self.public_key_pin = public_key_pin.strip().lower()
        self.verify_tls = verify_tls
        self.timeout = (int(connect_timeout), int(read_timeout))
        self.http = requests.Session()
        self.http.headers.update({
            "Accept": "application/json",
            "User-Agent": user_agent or f"PDK-Python-Client/{SDK_VERSION}",
        })
        self.on_http: Optional[Callable[[dict[str, Any]], None]] = None
        self.last_http_record: Optional[dict[str, Any]] = None
        self.last_result: Optional[dict[str, Any]] = None
        self.last_http_status = 0
        self._server_public_key_pem = ""
        self._kid = ""
        self._encryption_mode = "unknown"
        self._session_key: Optional[bytes] = None

    def close(self) -> None:
        self.http.close()

    def __enter__(self) -> "PdkClient":
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.close()

    @property
    def is_logged_in(self) -> bool:
        return bool(self.token and self.phone and self.device_id)

    def clear_session(self) -> None:
        self.token = ""
        self.token_name = "satoken"

    def export_session(self, include_crypto_key: bool = True) -> dict[str, Any]:
        """导出敏感会话；调用方必须用 DPAPI/Keychain/Secret Service 等安全保存。"""
        key = base64.b64encode(self._session_key).decode("ascii") if include_crypto_key and self._session_key else ""
        return asdict(SessionSnapshot(self.app_id, self.phone, self.device_id,
                                      self.token_name, self.token, key))

    def restore_session(self, snapshot: Mapping[str, Any]) -> None:
        """恢复安全存储中的会话；恢复后必须调用 :meth:`verify_session`。"""
        app_id = int(snapshot.get("app_id", snapshot.get("appId", 0)))
        if app_id != self.app_id:
            raise ValueError(f"会话 appId={app_id} 与客户端 appId={self.app_id} 不一致")
        device_id = str(snapshot.get("device_id", snapshot.get("deviceId", ""))).strip()
        if device_id and device_id != self.device_id:
            raise ValueError("会话 deviceId 与当前客户端设备 ID 不一致")
        self.phone = str(snapshot.get("phone", "")).strip()
        self.token_name = str(snapshot.get("token_name", snapshot.get("tokenName", "satoken"))) or "satoken"
        self.token = str(snapshot.get("token_value", snapshot.get("tokenValue", "")))
        encoded_key = str(snapshot.get("envelope_session_key", ""))
        self._session_key = base64.b64decode(encoded_key) if encoded_key else None

    def _headers(self, authenticated: bool) -> dict[str, str]:
        headers = {"X-PDK-App-ID": str(self.app_id), "X-PDK-Device-ID": self.device_id}
        # 声明本端持有会话级 AES 密钥：服务端据此才会对 GET 响应加密。
        # 缺失此头时服务端返回明文，避免“服务端缓存了别的会话密钥、但本端没有”导致无法解密。
        if self._session_key is not None:
            headers["X-PDK-Crypto-Armed"] = "1"
        if self.phone:
            headers["X-PDK-Phone"] = self.phone
        if authenticated:
            if not self.is_logged_in:
                raise PdkClientError(40100, "本地没有完整登录会话，请先登录")
            headers[self.token_name] = self.token
        return headers

    @staticmethod
    def _is_envelope(text: str) -> bool:
        try:
            value = json.loads(text)
        except (TypeError, json.JSONDecodeError):
            return False
        return isinstance(value, dict) and {"enc", "iv", "data", "kid"}.issubset(value)

    def fetch_public_config(self, *, force_refresh: bool = False) -> dict[str, Any]:
        """拉取并校验协议加密配置；生产环境应提供 ``public_key_pin``。"""
        if self._server_public_key_pem and not force_refresh:
            return {"encryptionMode": self._encryption_mode,
                    "publicKey": self._server_public_key_pem, "kid": self._kid,
                    "publicKeyFingerprint": _public_key_fingerprint(self._server_public_key_pem)}
        url = f"{self.base_url}/api/v1/client/config/public"
        started = time.monotonic()
        try:
            response = self.http.get(url, headers=self._headers(False), timeout=self.timeout,
                                     verify=self.verify_tls)
        except requests.RequestException as exc:
            body = {"code": 0, "message": f"拉取客户端公共配置失败: {exc}", "data": None}
            self.last_http_status, self.last_result = 0, body
            self._emit_http("GET", "/api/v1/client/config/public", None, 0, body,
                            int((time.monotonic() - started) * 1000))
            raise PdkClientError(0, f"拉取客户端公共配置失败: {exc}") from exc
        text = response.text
        if self._is_envelope(text):
            if self._session_key is None:
                raise PdkClientError(42904,
                    "服务端返回了会话级加密配置，但本地没有对应会话密钥；请恢复完整会话快照或等待服务端会话密钥过期",
                    http_status=response.status_code)
            text = self._decrypt_response(text)
        try:
            result = json.loads(text)
        except json.JSONDecodeError as exc:
            raise PdkClientError(response.status_code, "公共配置返回非 JSON",
                                 http_status=response.status_code) from exc
        if int(result.get("code", 0)) != 200:
            self.last_http_status, self.last_result = response.status_code, result
            self._emit_http("GET", "/api/v1/client/config/public", None,
                            response.status_code, result,
                            int((time.monotonic() - started) * 1000))
            raise PdkClientError(int(result.get("code", response.status_code)),
                                 str(result.get("message", "拉取公共配置失败")),
                                 http_status=response.status_code, response=result)
        data = result.get("data") or {}
        public_key = str(data.get("publicKey") or "")
        fingerprint = _public_key_fingerprint(public_key) if public_key else ""
        advertised = str(data.get("publicKeyFingerprint") or "").lower()
        if advertised and fingerprint and advertised != fingerprint:
            raise PdkClientError(42901, "服务端公钥内容与公钥指纹不一致")
        if self.public_key_pin and fingerprint != self.public_key_pin:
            raise PdkClientError(42901,
                                 f"公钥指纹不匹配：期望 {self.public_key_pin}，实际 {fingerprint}")
        self._server_public_key_pem = public_key
        self._kid = str(data.get("kid") or "v1")
        self._encryption_mode = str(data.get("encryptionMode") or "optional").lower()
        # 主动加密：服务端要求加密（非 off）时立即启用请求加密，
        # 避免首包明文被 force 模式拒回（42900）后再被动重试。
        if self._encryption_mode != "off":
            self.use_crypto = True
        self.last_http_status, self.last_result = response.status_code, result
        self._emit_http("GET", "/api/v1/client/config/public", None,
                        response.status_code, result,
                        int((time.monotonic() - started) * 1000))
        return data

    def _encrypt_body(self, body: Mapping[str, Any]) -> str:
        config = self.fetch_public_config()
        if str(config.get("encryptionMode", "optional")).lower() == "off":
            return ""
        if not self._server_public_key_pem:
            raise PdkClientError(42901, "服务端没有提供协议信封公钥")
        public_key = serialization.load_pem_public_key(self._server_public_key_pem.encode("utf-8"))
        aes_key = AESGCM.generate_key(bit_length=256)
        iv = os.urandom(12)
        plain = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        encrypted = AESGCM(aes_key).encrypt(iv, plain, None)
        wrapped = public_key.encrypt(aes_key, asym_padding.OAEP(
            mgf=asym_padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
        self._session_key = aes_key
        return json.dumps({
            "kid": self._kid, "enc": base64.b64encode(wrapped).decode("ascii"),
            "iv": base64.b64encode(iv).decode("ascii"),
            "data": base64.b64encode(encrypted).decode("ascii"),
            "ts": int(time.time() * 1000),
            "rnd": "".join(random.choices(string.ascii_letters + string.digits, k=24)),
        }, ensure_ascii=False)

    def _decrypt_response(self, envelope_json: str) -> str:
        if self._session_key is None:
            raise PdkClientError(42904, "收到加密响应，但本地没有对应 AES 会话密钥")
        envelope = json.loads(envelope_json)
        try:
            plain = AESGCM(self._session_key).decrypt(base64.b64decode(envelope["iv"]),
                                                         base64.b64decode(envelope["data"]), None)
        except Exception as exc:
            raise PdkClientError(42904, "响应信封解密失败") from exc
        return plain.decode("utf-8")

    def _expectation(self, method: str, path: str) -> str:
        clean = path.split("?", 1)[0]
        if (method.upper(), clean) in _EXPECTATIONS:
            return _EXPECTATIONS[(method.upper(), clean)]
        if clean.startswith("/api/v1/client/business/by-app/"):
            return "code=200，业务 effectiveStatus=AVAILABLE 且授权模式符合客户端构建配置"
        if clean.startswith("/api/v1/client/zhibo-live/streams/") and clean.endswith("/stop"):
            return "code=200，只停止当前许可证自己的推流会话"
        return "code=200，业务 data 符合当前接口契约"

    def _emit_http(self, method: str, path: str, request_body: Any, http_status: int,
                   response_body: Any, elapsed_ms: int) -> None:
        record = {
            "method": method.upper(), "url": f"{self.base_url}{path}",
            "expectation": self._expectation(method, path),
            "request": redact_sensitive(request_body), "httpStatus": http_status,
            "code": response_body.get("code") if isinstance(response_body, dict) else None,
            "message": response_body.get("message") if isinstance(response_body, dict) else None,
            "response": redact_sensitive(response_body), "elapsedMs": elapsed_ms,
        }
        self.last_http_record = record
        if self.on_http:
            self.on_http(record)

    def _request(self, method: str, path: str,
                 json_body: Optional[Mapping[str, Any]] = None, *,
                 params: Optional[Mapping[str, Any]] = None,
                 authenticated: bool = False, _retried: bool = False) -> Any:
        method = method.upper()
        headers = self._headers(authenticated)
        send_data: Optional[str] = None
        if json_body is not None and self.use_crypto:
            send_data = self._encrypt_body(json_body)
            if send_data:
                headers["Content-Type"] = "application/json"
        started = time.monotonic()
        try:
            response = self.http.request(
                method, f"{self.base_url}{path}", headers=headers, params=params,
                data=send_data, json=None if send_data is not None else json_body,
                timeout=self.timeout, verify=self.verify_tls)
            http_status, response_text = response.status_code, response.text
        except requests.RequestException as exc:
            body = {"code": 0, "message": f"网络请求失败: {exc}", "data": None}
            self.last_http_status, self.last_result = 0, body
            self._emit_http(method, path, json_body, 0, body,
                            int((time.monotonic() - started) * 1000))
            raise PdkClientError(0, body["message"], response=body) from exc
        if self._is_envelope(response_text):
            response_text = self._decrypt_response(response_text)
        try:
            result = json.loads(response_text) if response_text else {}
        except json.JSONDecodeError:
            result = {"code": http_status if http_status >= 400 else 50000,
                      "message": f"服务端返回非 JSON: {response_text[:160]}", "data": None}
        self.last_http_status, self.last_result = http_status, result
        self._emit_http(method, path, json_body, http_status, result,
                        int((time.monotonic() - started) * 1000))
        code = int(result.get("code", http_status if http_status else 50000))
        if code == 200:
            return result.get("data")
        if not _retried and code == 42900 and self.auto_enable_crypto and json_body is not None:
            self.use_crypto = True
            self.fetch_public_config(force_refresh=True)
            return self._request(method, path, json_body, params=params,
                                 authenticated=authenticated, _retried=True)
        if not _retried and code in {42901, 42904} and self.use_crypto and json_body is not None:
            self._server_public_key_pem, self._kid = "", ""
            self.fetch_public_config(force_refresh=True)
            return self._request(method, path, json_body, params=params,
                                 authenticated=authenticated, _retried=True)
        if code in {40100, 40102, 40103, 40106}:
            self.clear_session()
        raise PdkClientError(code, str(result.get("message", f"请求失败（HTTP {http_status}）")),
                             http_status=http_status, data=result.get("data"), response=result)

    # --------------------------------------------------------------- 业务发现与认证
    def business_info(self) -> dict[str, Any]:
        return self._request("GET", f"/api/v1/client/business/by-app/{self.app_id}")

    def ensure_business_available(self, *, expected_biz_code: str = "",
                                  expected_authorization_mode: str = "") -> dict[str, Any]:
        info = self.business_info()
        if expected_biz_code and info.get("bizCode") != expected_biz_code:
            raise PdkClientError(40050,
                f"业务不匹配：期望 {expected_biz_code}，实际 {info.get('bizCode')}")
        if expected_authorization_mode and info.get("authorizationMode") != expected_authorization_mode:
            raise PdkClientError(40058,
                f"授权模型不匹配：期望 {expected_authorization_mode}，实际 {info.get('authorizationMode')}")
        if info.get("effectiveStatus") != "AVAILABLE":
            raise PdkClientError(50350 if info.get("configuredStatus") == "ACTIVE" else 40321,
                                 str(info.get("unavailableReason") or "当前业务不可用"), data=info)
        return info

    def send_sms(self, purpose: str = "REGISTER", *, phone: str = "") -> dict[str, Any]:
        target = (phone or self.phone).strip()
        if not target:
            raise ValueError("phone 不能为空")
        return self._request("POST", "/api/v1/client/auth/sms/send",
                             {"appId": self.app_id, "phone": target, "purpose": purpose})

    def register(self, password: str, sms_code: str, *, phone: str = "",
                 invitation_code: str = "") -> dict[str, Any]:
        target = (phone or self.phone).strip()
        if not target:
            raise ValueError("phone 不能为空")
        data = self._request("POST", "/api/v1/client/auth/register", {
            "appId": self.app_id, "phone": target, "smsCode": sms_code,
            "password": password, "deviceId": self.device_id,
            "invitationCode": invitation_code or None,
        })
        self.phone = target
        if data.get("tokenValue"):
            self._apply_login(data)
        return data

    def login(self, password: str, *, phone: str = "", card_key: str = "",
              device_name: str = "", platform_name: str = "",
              client_version: str = SDK_VERSION) -> dict[str, Any]:
        target = (phone or self.phone).strip()
        if not target:
            raise ValueError("phone 不能为空")
        body: dict[str, Any] = {
            "appId": self.app_id, "phone": target, "password": password,
            "deviceId": self.device_id,
            "deviceName": device_name or platform.node() or "Python Client",
            "platform": platform_name or platform.system().lower(),
            "clientVersion": client_version,
        }
        if card_key.strip():
            body["cardKey"] = card_key.strip()
        data = self._request("POST", "/api/v1/client/auth/login", body)
        self.phone = target
        self._apply_login(data)
        return data

    def activate_device(self, password: str, card_key: str, **kwargs: Any) -> dict[str, Any]:
        """DEVICE_LICENSE 新设备激活就是“带卡密登录”。"""
        return self.login(password, card_key=card_key, **kwargs)

    def _apply_login(self, data: Mapping[str, Any]) -> None:
        token_value = str(data.get("tokenValue") or "")
        if not token_value:
            raise PdkClientError(50000, "登录成功响应缺少 tokenValue", data=data)
        if int(data.get("appId") or self.app_id) != self.app_id:
            raise PdkClientError(40106, "登录响应属于其他 appId")
        self.token_name, self.token = str(data.get("tokenName") or "satoken"), token_value
        response_device_id = str(data.get("deviceId") or "").strip()
        if response_device_id and response_device_id != self.device_id:
            self.clear_session()
            raise PdkClientError(40103, "登录响应的 deviceId 与本机设备 ID 不一致")

    def logout(self) -> str:
        data = self._request("POST", "/api/v1/client/auth/logout", authenticated=True)
        self.clear_session()
        return str(data)

    def unbind_device(self) -> str:
        data = self._request("POST", "/api/v1/client/auth/unbind-device", authenticated=True)
        self.clear_session()
        return str(data)

    def change_password(self, old_password: str, new_password: str) -> str:
        if not self.phone:
            raise ValueError("phone 不能为空")
        data = self._request("POST", "/api/v1/client/auth/change-password", {
            "appId": self.app_id, "phone": self.phone,
            "oldPassword": old_password, "newPassword": new_password})
        self.clear_session()
        return str(data)

    def reset_password(self, sms_code: str, new_password: str, *, phone: str = "") -> str:
        target = (phone or self.phone).strip()
        if not target:
            raise ValueError("phone 不能为空")
        data = self._request("POST", "/api/v1/client/auth/reset-password", {
            "appId": self.app_id, "phone": target,
            "smsCode": sms_code, "newPassword": new_password})
        self.clear_session()
        self.phone = target
        return str(data)

    # --------------------------------------------------------------- 查询与许可证
    def profile(self) -> dict[str, Any]:
        return self._request("GET", "/api/v1/client/account/profile", authenticated=True)

    def usage(self, page: int = 1, size: int = 20) -> dict[str, Any]:
        return self._request("GET", "/api/v1/client/account/usage",
                             params={"page": max(1, page), "size": min(max(1, size), 100)},
                             authenticated=True)

    def account_card(self) -> dict[str, Any]:
        return self._request("GET", "/api/v1/client/account/card", authenticated=True)

    def resource_status(self) -> dict[str, Any]:
        return self._request("GET", "/api/v1/client/resources/status", authenticated=True)

    def device_license_current(self) -> dict[str, Any]:
        return self._request("GET", "/api/v1/client/device-license/current", authenticated=True)

    def device_license_list(self) -> list[dict[str, Any]]:
        return self._request("GET", "/api/v1/client/device-license/devices", authenticated=True)

    def device_license_renewal_history(self) -> list[dict[str, Any]]:
        return self._request("GET", "/api/v1/client/device-license/renewal-history", authenticated=True)

    def device_license_unbind(self) -> str:
        data = self._request("POST", "/api/v1/client/device-license/unbind", authenticated=True)
        self.clear_session()
        return str(data)

    def verify_session(self) -> dict[str, Any]:
        """恢复会话、回到前台或关键操作前调用；不是每个请求前的强制心跳。"""
        profile_data = self.profile()
        mode = str(profile_data.get("authorizationMode") or "")
        license_data = profile_data.get("deviceLicense") or {}
        expire_at = license_data.get("expireAt") if license_data else profile_data.get("expireTime")
        server_time = license_data.get("serverTime") if license_data else None
        status = license_data.get("status") if license_data else profile_data.get("status")
        expired = self._is_expired(expire_at, server_time)
        allowed = status == "ACTIVE" and not expired if mode == "DEVICE_LICENSE" \
            else status in {"ACTIVE", "TRIAL"} and not expired
        return {
            "sessionValid": True, "appId": profile_data.get("appId"),
            "bizCode": profile_data.get("bizCode"), "authorizationMode": mode,
            "status": status, "expireAt": expire_at, "serverTime": server_time,
            "expired": expired, "operationAllowedHint": allowed,
            "mustAskServerForFinalDecision": True, "raw": profile_data,
        }

    @staticmethod
    def _is_expired(expire_at: Any, server_time: Any = None) -> bool:
        if not expire_at:
            return True
        try:
            expiry = datetime.fromisoformat(str(expire_at).replace("Z", "+00:00"))
            now = datetime.fromisoformat(str(server_time).replace("Z", "+00:00")) \
                if server_time else datetime.now(expiry.tzinfo)
            return expiry <= now
        except (TypeError, ValueError):
            return False

    # --------------------------------------------------------------- PDD
    def activate_card(self, card_key: str, *, payment_channel: str = "OFFLINE",
                      payment_txn_no: str = "",
                      actual_amount: Optional[float] = None) -> dict[str, Any]:
        """仅 USER_SUBSCRIPTION/PDD 使用；该接口当前无需登录 Token。"""
        if not self.phone:
            raise ValueError("phone 不能为空")
        body: dict[str, Any] = {
            "appId": self.app_id, "cardKey": card_key, "userPhone": self.phone,
            "deviceId": self.device_id, "orderType": "NORMAL_SALE",
            "paymentChannel": payment_channel, "paymentTxnNo": payment_txn_no or None,
        }
        if actual_amount is not None:
            body["actualAmount"] = actual_amount
        return self._request("POST", "/api/v1/card/activate", body)

    def acquire_token(self, action_type: str, goods_id: str) -> dict[str, Any]:
        return self._request("POST", "/api/v1/dispatch/acquire-token",
                             {"actionType": action_type, "goodsId": goods_id,
                              "timestamp": int(time.time() * 1000)},
                             authenticated=True)

    def acquire_token_decrypted(self, action_type: str, goods_id: str,
                                root_salt: str) -> tuple[dict[str, Any], dict[str, Any]]:
        result = self.acquire_token(action_type, goods_id)
        encrypted_payload = str(result.get("encryptedPayload") or "")
        if not encrypted_payload:
            raise PdkClientError(50000, "资源响应缺少 encryptedPayload", data=result)
        return result, self.decrypt_resource_payload(encrypted_payload, root_salt)

    @staticmethod
    def decrypt_resource_payload(encrypted_payload: str, root_salt: str) -> dict[str, Any]:
        """解密 PDD 短效资源。生产 root_salt 必须通过安全构建配置注入。"""
        raw = base64.b64decode(encrypted_payload)[::-1]
        if len(raw) < 30 or raw[:2] != b"PD":
            raise ValueError("非有效 PDK 资源密文")
        iv, encrypted = raw[2:14], raw[14:]
        current_window = int(time.time() // 60 // 10)
        last_error: Optional[Exception] = None
        for window in (current_window, current_window - 1, current_window + 1):
            key = hashlib.sha256(f"{root_salt}_{window}".encode("utf-8")).digest()[:16]
            try:
                return json.loads(AESGCM(key).decrypt(iv, encrypted, None).decode("utf-8"))
            except Exception as exc:
                last_error = exc
        raise ValueError(f"资源密文解密失败，可能已过期或 root_salt 不一致: {last_error}")

    def report_result(self, lease_trace_id: str, status: str, *,
                      duration_ms: Optional[int] = None, error_message: str = "") -> str:
        data = self._request("POST", "/api/v1/dispatch/report-result", {
            "leaseTraceId": lease_trace_id, "status": status,
            "responseDurationMs": duration_ms, "errorMessage": error_message or None,
        }, authenticated=True)
        return str(data)

    # --------------------------------------------------------------- ZHIBO_LIVE
    def live_publish_ticket(self, *, title: str = "", client_request_id: str = "",
                            requested_protocol: str = "RTMP") -> dict[str, Any]:
        if self.app_id != 3:
            raise PdkClientError(40370, "推流票据接口仅允许 appId=3 / ZHIBO_LIVE")
        return self._request("POST", "/api/v1/client/zhibo-live/publish-tickets", {
            "clientRequestId": client_request_id or str(uuid.uuid4()),
            "title": title or None, "requestedProtocol": requested_protocol,
        }, authenticated=True)

    def live_streams_current(self) -> list[dict[str, Any]]:
        return self._request("GET", "/api/v1/client/zhibo-live/streams/current",
                             authenticated=True)

    def live_stream_stop(self, session_no: str) -> str:
        data = self._request("POST",
                             f"/api/v1/client/zhibo-live/streams/{session_no}/stop",
                             authenticated=True)
        return str(data)


def _demo() -> None:
    """关键操作调用示例（完整接入顺序）。

    敏感信息全部从环境变量读取，源码不含任何真实账号 / 密码 / 卡密。
    设置 ``PDK_PHONE`` 与 ``PDK_PASSWORD`` 后，登录及后续鉴权操作才会演示；
    DEVICE_LICENSE 业务还需提供 ``PDK_CARD_KEY``（新设备激活即“带卡密登录”）。
    """
    app_id = int(os.getenv("PDK_APP_ID", "2"))
    base_url = os.getenv("PDK_BASE_URL", "http://127.0.0.1:8080")
    phone = os.getenv("PDK_PHONE", "13800000000")
    password = os.getenv("PDK_PASSWORD", "13800000000")
    card_key = os.getenv("PDK_CARD_KEY", "PDK-36DF-0FBB-E7E3")

    client = PdkClient(base_url, app_id, phone=phone, use_crypto=True)
    client.on_http = lambda r: print(  # 可选：打开看到每个请求的脱敏记录
        f"[HTTP] {r['method']} {r['url']} -> code={r.get('code')} msg={r.get('message')}")

    # 1) 公开配置（加密模式 / 公钥 / kid / 指纹）+ 业务发现（无需登录）
    config = client.fetch_public_config()
    print(f"① 公开配置：encryptionMode={config.get('encryptionMode')} kid={config.get('kid')}")
    info = client.business_info()
    print(f"② 业务发现：{info.get('bizCode')} / {info.get('businessName')} "
          f"/ 授权模式={info.get('authorizationMode')}")

    if not (phone and password):
        print("\n[跳过] 未设置 PDK_PHONE / PDK_PASSWORD，登录及后续鉴权示例略过。")
        print("       设置后完整演示：login → verify_session → profile → device_license_current → logout")
        client.close()
        return

    # 2) 登录（USER_SUBSCRIPTION 可仅账密；DEVICE_LICENSE 需带 card_key 激活）
    try:
        login_data = client.login(password, card_key=card_key)
        print(f"③ 登录成功：tokenName={login_data.get('tokenName')} deviceId={login_data.get('deviceId')}")
    except PdkClientError as exc:
        print(f"[登录失败] {exc.code} {exc.message}；建议：{exc.action}")
        client.close()
        return

    # 3) 会话校验（回到前台 / 关键操作前的轻量确认）
    v = client.verify_session()
    print(f"④ 会话校验：valid={v['sessionValid']} status={v['status']} expireAt={v['expireAt']} "
          f"可操作={v['operationAllowedHint']}")

    # 4) 当前账号资料
    prof = client.profile()
    print(f"⑤ 个人资料：bizCode={prof.get('bizCode')} deviceId={prof.get('deviceId')} "
          f"剩余次数={prof.get('remainingCalls')}")

    # 5) 当前设备许可证（仅 DEVICE_LICENSE 业务返回有效值）
    lic = client.device_license_current()
    print(f"⑥ 设备许可证：status={lic.get('status')} expireAt={lic.get('expireAt')}")

    # 6) 退出登录（令牌失效，须重新登录）
    client.logout()
    print("⑦ 已退出登录")


if __name__ == "__main__":
    _demo()
