"""临时校验脚本：用与后端 AesByteFlipUtils.encryptAndFlip 完全一致的算法生成密文，
再用 SDK 的 decrypt_token 解密，验证算法对称、跨语言一致。"""
import base64
import hashlib
import json
import os
import sys
import time

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT_SALT = "PDK_SECRET_SALT_2026_ENTERPRISE"
sys.path.insert(0, os.path.join(os.path.dirname(__file__)))
from pdk import PdkApiClient, State, Event  # noqa: E402


def encrypt_and_flip(plaintext: str) -> str:
    """镜像后端 AesByteFlipUtils.encryptAndFlip。"""
    import hashlib
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    window = int(time.time() // 60 // 10)
    key = hashlib.sha256(f"{ROOT_SALT}_{window}".encode()).digest()[:16]
    iv = bytes(range(12))  # 固定 IV 便于复现（后端用随机 IV）
    ct = AESGCM(key).encrypt(iv, plaintext.encode(), None)
    raw = b"PD" + iv + ct
    flipped = raw[::-1]
    return base64.b64encode(flipped).decode()


def main():
    # 1) 包导入 & 枚举值校验
    assert State.LoggedIn == 6 and State.Kicked == 14, "State 枚举值不匹配契约"
    assert Event.DecryptSucceeded == 102, "Event 枚举值不匹配契约"
    print("[OK] 包导入成功，State/Event 枚举值与契约一致")

    # 2) 回调接线
    seen = []
    c = PdkApiClient(base_url="http://127.0.0.1:9")  # 故意不可达，仅验证回调与状态机
    c.on_state = lambda s, d: seen.append(("state", s, d))
    c.on_event = lambda e, m: seen.append(("event", e, m))
    c.on_log = lambda line: seen.append(("log", line))
    assert c.last_state == State.Ready, "初始化状态应为 Ready"
    assert c.app_id == 1, "旧调用必须默认使用 PDD appId=1"
    print("[OK] 回调接线 / 初始状态 Ready 正常")

    # 2.5) appId 必须同时进入请求头和公开接口请求体
    captured = {}

    class FakeResponse:
        status_code = 200
        text = '{"code":200,"message":"ok","data":{}}'

        @staticmethod
        def json():
            return {"code": 200, "message": "ok", "data": {}}

    client_app2 = PdkApiClient(base_url="http://example.invalid", app_id=2)
    safe_logs = []
    client_app2.on_log = safe_logs.append

    def fake_request(method, url, **kwargs):
        captured.update({"method": method, "url": url, **kwargs})
        return FakeResponse()

    client_app2.http.request = fake_request
    client_app2.send_sms("13800138000")
    assert captured["headers"]["X-PDK-App-ID"] == "2"
    assert captured["json"]["appId"] == 2
    client_app2.register("13800138000", "Secret123", "123456", "ABCDEF")
    client_app2.login("13800138000", "Secret123")
    client_app2.change_password("13800138000", "Secret123", "Secret456")
    client_app2.activate_card("PDK-AAAA-BBBB-CCCC", "13800138000")
    client_app2.acquire_token("GOODS_COLLECT", "10001")
    client_app2.report_result("TRACE-1", "SUCCESS")
    assert captured["headers"]["X-PDK-App-ID"] == "2"
    assert "Secret123" not in "\n".join(safe_logs)
    assert "123456" not in "\n".join(safe_logs)
    assert "PDK-AAAA-BBBB-CCCC" not in "\n".join(safe_logs)
    print("[OK] appId 已同时写入 X-PDK-App-ID 与公开接口请求体")
    print("[OK] Python SDK 全部 POST 方法可调用，调试日志已脱敏")

    # 3) 加密->解密 往返（验证与后端算法对称）
    plain_obj = {"pddSession": "sess-abc-123", "cookie": "xxx"}
    cipher = encrypt_and_flip(json.dumps(plain_obj))
    client = PdkApiClient()
    got = client.decrypt_token(cipher)
    assert json.loads(got) == plain_obj, f"解密结果不一致: {got}"
    print("[OK] AES-128-GCM + 字节翻转 解密与后端算法对称，明文还原正确")

    # 4) 时间窗容错：用上一个窗口的密钥加密，仍能解密
    window_prev = int(time.time() // 60 // 10) - 1
    key = hashlib.sha256(f"{ROOT_SALT}_{window_prev}".encode()).digest()[:16]
    iv = bytes(range(12))
    ct = AESGCM(key).encrypt(iv, b'{"t":1}', None)
    flipped = (b"PD" + iv + ct)[::-1]
    cipher2 = base64.b64encode(flipped).decode()
    assert json.loads(client.decrypt_token(cipher2)) == {"t": 1}
    print("[OK] ±1 时间窗容错解密正常")

    print("\n全部校验通过")


if __name__ == "__main__":
    main()
