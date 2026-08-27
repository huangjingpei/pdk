"""P0 公钥指纹钉扎 单元测试。

验证三件事：
1. 指纹计算稳定（同公钥同指纹；不同公钥不同指纹；与 PEM 空白无关）。
2. 钉扎校验通过：期望指纹正确时 fetch_public_config_pinned 正常返回。
3. 钉扎校验拒绝：期望指纹错误时抛 PublicKeyPinMismatchError。

用内嵌 mock HTTP 服务端模拟 /api/v1/client/config/public，无需真实后端。
"""
import base64
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from pdk.crypto import (compute_public_key_fingerprint, fetch_public_config_pinned,
                        PublicKeyPinMismatchError)


def _gen_key_pair():
    """生成一对 RSA-2048 测试密钥，返回 (private_pem, public_pem)。"""
    priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    priv_pem = priv.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    ).decode("ascii")
    pub_pem = priv.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode("ascii")
    return priv_pem, pub_pem


class _MockHandler(BaseHTTPRequestHandler):
    """模拟 /api/v1/client/config/public 返回指定公钥配置。"""
    public_key_pem = ""
    mode = "optional"
    kid = "v1"

    def do_GET(self):
        if self.path == "/api/v1/client/config/public":
            body = json.dumps({"code": 200, "message": "", "data": {
                "encryptionMode": _MockHandler.mode,
                "publicKey": _MockHandler.public_key_pem,
                "kid": _MockHandler.kid,
            }}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, *args):
        pass  # 静默


class FingerprintPinTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.priv1, cls.pub1 = _gen_key_pair()
        cls.priv2, cls.pub2 = _gen_key_pair()
        cls.server = HTTPServer(("127.0.0.1", 0), _MockHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def _base_url(self):
        return f"http://127.0.0.1:{self.port}"

    def test_fingerprint_stable_and_key_independent(self):
        """同公钥同指纹；不同公钥不同指纹；PEM 空白不影响指纹。"""
        fp1 = compute_public_key_fingerprint(self.pub1)
        fp1_again = compute_public_key_fingerprint(self.pub1)
        self.assertEqual(fp1, fp1_again, "同一公钥指纹应稳定")
        self.assertEqual(len(fp1), 32, "指纹应为 32 字符 hex")

        fp2 = compute_public_key_fingerprint(self.pub2)
        self.assertNotEqual(fp1, fp2, "不同公钥指纹应不同")

        # PEM 去掉换行后指纹应一致（只取决于公钥本身）
        pub_squashed = self.pub1.replace("\n", "")
        self.assertEqual(compute_public_key_fingerprint(pub_squashed), fp1,
                         "PEM 空白不应影响指纹")

    def test_pin_match_passes(self):
        """期望指纹正确时，拉取并校验应正常返回。"""
        _MockHandler.public_key_pem = self.pub1
        _MockHandler.mode = "optional"
        expected_fp = compute_public_key_fingerprint(self.pub1)
        cfg = fetch_public_config_pinned(self._base_url(), expected_fp)
        data = cfg["data"]
        self.assertEqual(data["publicKey"], self.pub1)
        self.assertEqual(data["kid"], "v1")

    def test_pin_mismatch_raises(self):
        """期望指纹错误时，应抛 PublicKeyPinMismatchError。"""
        _MockHandler.public_key_pem = self.pub1  # 实际公钥1
        wrong_fp = "0" * 32  # 故意错的指纹
        with self.assertRaises(PublicKeyPinMismatchError):
            fetch_public_config_pinned(self._base_url(), wrong_fp)

    def test_empty_pin_skips_check(self):
        """期望指纹为空时退化为不校验（向后兼容）。"""
        _MockHandler.public_key_pem = self.pub1
        cfg = fetch_public_config_pinned(self._base_url(), "")
        self.assertEqual(cfg["data"]["publicKey"], self.pub1)

    def test_pin_catches_mitm_swap(self):
        """模拟 MITM 把公钥换成攻击者的：钉扎应拦住。"""
        # 客户端持有公钥1的指纹作为期望
        expected_fp = compute_public_key_fingerprint(self.pub1)
        # 但服务端被 MITM，返回公钥2
        _MockHandler.public_key_pem = self.pub2
        with self.assertRaises(PublicKeyPinMismatchError):
            fetch_public_config_pinned(self._base_url(), expected_fp)


if __name__ == "__main__":
    unittest.main(verbosity=2)
