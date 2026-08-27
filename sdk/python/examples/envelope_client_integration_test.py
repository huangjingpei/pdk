"""客户端信封加密 端到端集成测试（含真实 HTTP）。

用标准库起一个 mock 后端：实现 /api/v1/client/config/public 下发公钥，
并实现 /api/v1/client/echo 的解密与响应加密（与后端 BodyCryptoService 等价）。
验证 PdkApiClient(auto_envelope=True) 的全链路：请求加密 -> 服务端解密 -> 响应加密 -> 客户端解密。

运行：python examples/envelope_client_integration_test.py
"""
import base64
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from pdk.client import PdkApiClient
from pdk.crypto import is_envelope


# 服务端私钥（模拟 backend SecurityKeyService）
_PRIV = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_PUB_PEM = _PRIV.public_key().public_bytes(
    encoding=serialization.Encoding.PEM,
    format=serialization.PublicFormat.SubjectPublicKeyInfo,
).decode("utf-8")


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # 静默
        pass

    def _send_json(self, obj, raw=None):
        payload = raw if raw is not None else json.dumps(obj).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/api/v1/client/config/public":
            self._send_json({"code": 200, "message":  "ok",
                             "data": {"encryptionMode": "optional",
                                      "publicKey": _PUB_PEM, "kid": "v1"}})
        else:
            self._send_json({"code": 404, "message": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        text = self.rfile.read(length).decode("utf-8", "replace") if length else ""

        if is_envelope(text):
            env = json.loads(text)
            wrapped = base64.b64decode(env["enc"])
            aes = _PRIV.decrypt(
                wrapped,
                padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
            )
            iv = base64.b64decode(env["iv"])
            data = base64.b64decode(env["data"])
            plain = AESGCM(aes).decrypt(iv, data, None).decode("utf-8")
            req_obj = json.loads(plain)
            # 服务端用同一会话密钥加密响应（与 encryptResponse 等价）
            resp_plain = json.dumps({"code": 200, "message": "ok",
                                     "data": {"echo": req_obj, "received": "encrypted"}})
            iv2 = os.urandom(12)
            ct = AESGCM(aes).encrypt(iv2, resp_plain.encode("utf-8"), None)
            resp_env = json.dumps({
                "kid": "v1",
                "enc": base64.b64encode(aes).decode("ascii"),
                "iv": base64.b64encode(iv2).decode("ascii"),
                "data": base64.b64encode(ct).decode("ascii"),
                "ts": int(time.time() * 1000),
                "rnd": base64.b64encode(os.urandom(8)).decode("ascii"),
            })
            self._send_json(None, raw=resp_env.encode("utf-8"))
        else:
            self._send_json({"code": 200, "message": "ok",
                             "data": {"echo": text, "received": "plain"}})


def main() -> int:
    server = HTTPServer(("127.0.0.1", 0), _Handler)
    port = server.server_address[1]
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()

    try:
        client = PdkApiClient(base_url=f"http://127.0.0.1:{port}", auto_envelope=True)
        # 验证：auto_envelope 已拉取公钥
        assert client.envelope_public_key, "未成功拉取服务端公钥"

        body = client.request("POST", "/api/v1/client/echo", json_body={"hello": "world", "n": 42})
        assert body.get("code") == 200, f"响应异常: {body}"
        data = body.get("data") or {}
        assert data.get("received") == "encrypted", "服务端未识别为加密请求"
        assert data.get("echo") == {"hello": "world", "n": 42}, "回显内容不一致"
        print("[OK] 客户端 auto_envelope 全链路：请求加密 -> 服务端解密 -> 响应加密 -> 客户端解密，内容一致")
    finally:
        server.shutdown()

    print("\n✅ 客户端集成测试通过（真实 HTTP 端到端）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
