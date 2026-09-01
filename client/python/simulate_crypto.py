"""
PDK 客户端通信加密行为测试（基于真实后端 localhost:8080）

验证点（对应 docs/protocol-encryption-design.md）：
  C1 加密登录端到端可用（请求体加密为信封、响应自动解密）
  C2 会话级 GET 响应加密：加密登录后 GET profile 被服务端用会话密钥加密、客户端用本地会话密钥解密
  C3 灰度/强制：明文客户端（use_crypto=False）登录行为随 mode 自适应
     （optional/off 下可登录；force 下必须被拒 42900，不允许明文）
  C1/C2/C5/C6 为加密客户端专属测试：off 模式下服务端不处理信封，这四项自动 SKIP（非失败）。
  C4 抓包对比（wire 层）：加密请求的 body 是信封密文、明文请求的 body 是明文 JSON；
                         加密登录后的 GET 响应是密文信封、明文登录后的 GET 响应是明文 CommonResult
  C5 篡改检测：信封 data 被篡改 → 后端 42904（GCM 认证失败/解密失败）
  C6 密钥版本不匹配：信封 kid 不存在(v9) → 后端 42901

说明：client/python/pdk_client.py 未实现公钥指纹钉扎（仅文档 §11.1 的 sdk/python PdkApiClient 有），
      故本脚本不覆盖钉扎测试。脚本会自动拉取 /api/v1/client/config/public 的 encryptionMode，
      C3 据此自适应断言（force 下明文必须被拒 42900，optional/off 下明文可登录）；
      C1/C2/C5/C6 在 off 模式自动跳过（服务端不再解密信封，加密客户端测试不适用）。
前置：user1(biz3) 密码已统一为 13800000000；PDK-EB35 / PDK-09A1 复位 UNBOUND。
"""
import sys
import os
import time
import random
import string
import base64
import subprocess
import json
import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

sys.path.insert(0, ".")
from pdk_client import PdkClient, PdkClientError

BASE_URL = "http://localhost:8080"
PHONE = "13800000000"
PWD = "13800000000"
CARD_ENC = "PDK-EB35-2C8B-8563"    # biz 3 加密登录用
CARD_PLAIN = "PDK-09A1-3194-B7AB"  # biz 3 明文灰度用
RUN = time.strftime("%Y%m%d%H%M%S")
DEV_ENC = f"XENC-{RUN}-PC"
DEV_PLAIN = f"XPLAIN-{RUN}-PC"

results = []


def check(desc, cond, detail=""):
    ok = bool(cond)
    results.append(ok)
    print(f"  [{'PASS' if ok else 'FAIL'}] {desc}" + (f"  -> {detail}" if detail else ""))


def line(t):
    print("\n" + "=" * 70 + f"\n{t}\n" + "=" * 70)


skipped = []


def skip(desc, reason):
    skipped.append(desc)
    print(f"  [SKIP] {desc}  -> {reason}")


def fake_fp(tag):
    return {
        "motherboardSerial": f"MB-{RUN}-{tag}-ENC01",
        "diskSerial": f"DSK-{RUN}-{tag}-ENC02",
        "cpuid": f"CPU-{RUN}-{tag}-ENC03",
    }


def make_login_body(device_id, card_key):
    return {
        "appId": 3, "phone": PHONE, "password": PWD, "deviceId": device_id,
        "deviceName": "crypto-test", "platform": "python", "clientVersion": "1.0.0",
        "cardKey": card_key, "fingerprint": fake_fp("X"),
    }


def raw_post_envelope(client, envelope_dict, device_id=None):
    """直接发 requests（绕过客户端解密），返回 (status, raw_text)。"""
    url = f"{BASE_URL}/api/v1/client/auth/login"
    headers = client._headers()
    if device_id:
        headers["X-PDK-Device-ID"] = device_id
    r = requests.post(url, data=json.dumps(envelope_dict), headers=headers, timeout=15)
    return r.status_code, r.text


def decrypt_envelope_with_key(raw_text, session_key):
    """用给定会话密钥解密信封，返回内部 CommonResult(dict)。"""
    o = json.loads(raw_text)
    iv = base64.b64decode(o["iv"])
    data = base64.b64decode(o["data"])
    pt = AESGCM(session_key).decrypt(iv, data, None)
    return json.loads(pt.decode("utf-8"))


def read_response_code(raw_text, session_key):
    """从响应读取 CommonResult.code。

    服务端对【请求是信封】的响应（含错误响应 42904/42901）也会用会话密钥加密返回，
    故先判断是否为信封：是则用 session_key 解密后取 code；否则直接取明文 code。
    session_key 必须是建立该会话的登录密钥（C5/C6 构造信封会覆盖 client._session_key）。
    """
    try:
        o = json.loads(raw_text)
    except Exception:
        return None
    if isinstance(o, dict) and PdkClient._is_envelope(raw_text):
        try:
            return decrypt_envelope_with_key(raw_text, session_key).get("code")
        except Exception:
            return None
    return o.get("code") if isinstance(o, dict) else None


def get_encryption_mode():
    """从免鉴权公开端点读取当前 encryptionMode（optional / off / force）。"""
    try:
        r = requests.get(f"{BASE_URL}/api/v1/client/config/public", timeout=15)
        if r.ok:
            return r.json().get("data", {}).get("encryptionMode", "optional")
    except Exception:
        pass
    return "optional"


def reset_cards():
    MYSQL_EXE = "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
    DSN = ["-u", "root", "-p89dfu*#$ewr87l", "-h", "127.0.0.1", "pdk_biz_db"]
    cards = sorted({CARD_ENC, CARD_PLAIN})

    def mq(sql):
        r = subprocess.run([MYSQL_EXE] + DSN + ["-e", sql], capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(r.stderr.strip())
        return r.stdout

    try:
        out = mq("SELECT ck.card_key, ck.id, dl.id, dl.user_device_id "
                 "FROM pdk_card_key ck LEFT JOIN pdk_device_license dl ON dl.card_key_id=ck.id "
                 f"WHERE ck.card_key IN ({','.join(repr(c) for c in cards)});")
        for ln in out.strip().splitlines()[1:]:
            if not ln.strip():
                continue
            parts = ln.split("\t")
            ck_id, dl_id, dev_id = parts[1], parts[2], parts[3]
            if dl_id and dl_id != "NULL":
                mq(f"UPDATE pdk_device_license SET user_device_id=NULL, status='UNBOUND', "
                   f"activated_at=NULL, effective_at=NULL, expire_at=NULL, version=version+1 WHERE id={dl_id};")
                if dev_id and dev_id != "NULL":
                    mq(f"UPDATE pdk_user_device SET status='UNBOUND' WHERE id={dev_id};")
            mq(f"UPDATE pdk_card_key SET status='ASSIGNED', activated_at=NULL, "
               f"activated_by_user_id=NULL, activated_by_phone=NULL, activated_device_id=NULL WHERE id={ck_id};")
        print("  [reset] 测试卡已复位 UNBOUND: " + ", ".join(cards))
    except Exception as e:
        print(f"  [reset warn] {e}")


# =====================================================================
# 幂等复位
# =====================================================================
line("准备：幂等复位测试卡")
reset_cards()

# 读取当前加密模式，决定加密客户端测试（C1/C2/C5/C6）是否适用
mode = get_encryption_mode()
line(f"当前 encryptionMode = {mode}（off=关闭信封处理；optional=灰度；force=强制仅信封）")

# =====================================================================
# C1：加密登录端到端
# =====================================================================
line("C1【加密登录】 use_crypto=True 端到端（请求加密 + 响应解密）")
c_enc = PdkClient(BASE_URL, app_id=3, phone=PHONE, device_id=DEV_ENC, use_crypto=True, public_key_pin="")
c1_ok = False
if mode == "off":
    skip("C1: 加密登录成功，拿到 token", "off 模式服务端不处理信封，加密客户端测试不适用（跳过）")
    skip("C1: 登录响应已自动解密（含 deviceLicense）", "依赖加密登录，随上条一并跳过")
else:
    try:
        r = c_enc.login(password=PWD, card_key=CARD_ENC)
        c1_ok = r.get("tokenValue") is not None
        check("C1: 加密登录成功，拿到 token", c1_ok, str(r.get("status")))
        check("C1: 登录响应已自动解密（含 deviceLicense）", r.get("deviceLicense") is not None)
    except PdkClientError as e:
        check("C1: 加密登录成功，拿到 token", False, f"[{e.code}] {e.message}")

# 保存 C1 登录建立的会话密钥（C5/C6 构造信封会覆盖 client._session_key，故先存）
c1_session = c_enc._session_key

# =====================================================================
# C2：会话级 GET 响应加密（加密登录后 GET profile 应被加密并解密）
# =====================================================================
line("C2【会话级 GET 加密】 加密登录后 GET profile 被服务端会话密钥加密、客户端解密")
if mode == "off" or not c1_ok:
    skip("C2: 加密客户端 profile() 正常返回（GET 响应密文已解密）",
         "off 模式不支持加密 或 C1 未成功建立会话，跳过")
else:
    try:
        prof = c_enc.profile()
        ok = prof.get("deviceLicense") is not None or prof.get("authorizationMode") is not None
        check("C2: 加密客户端 profile() 正常返回（GET 响应密文已解密）", ok, str(list(prof.keys())))
    except PdkClientError as e:
        check("C2: 加密客户端 profile() 正常返回（GET 响应密文已解密）", False, f"[{e.code}] {e.message}")

# =====================================================================
# C3：optional 灰度 —— 明文客户端同样可登录
# =====================================================================
# 注意：mode 已在脚本顶部读取，此处复用
line(f"C3【灰度/强制·mode={mode}】 明文客户端（use_crypto=False）登录行为随 mode 自适应")
c_plain = PdkClient(BASE_URL, app_id=3, phone=PHONE, device_id=DEV_PLAIN, use_crypto=False)
try:
    r = c_plain.login(password=PWD, card_key=CARD_PLAIN)
    ok = r.get("tokenValue") is not None
    if mode == "force":
        # force 本就不允许明文：明文竟登录成功才是异常
        check("C3: force 模式下明文登录被正确拒绝（42900，不允许明文）", False,
              "明文竟登录成功（不符合 force 预期）")
    else:
        check("C3: 明文登录成功（optional/off 下新旧客户端并存）", ok)
except PdkClientError as e:
    if mode == "force":
        check("C3: force 模式下明文登录被正确拒绝（42900）", e.code == 42900, f"[{e.code}] {e.message}")
    else:
        check("C3: 明文登录成功（optional/off 下新旧客户端并存）", False, f"[{e.code}] {e.message}")

# =====================================================================
# C4：抓包对比（wire 层密文 vs 明文）
# =====================================================================
line("C4【抓包对比】 加密 vs 明文的 wire 形态")
# 加密请求体（信封）
enc_envelope = json.loads(c_enc._encrypt_body(json.dumps(make_login_body(DEV_ENC, CARD_ENC))))
check("C4: 加密请求 wire body 是信封（含 enc/data/iv/kid，无 phone/password 明文）",
      all(k in enc_envelope for k in ("enc", "data", "iv", "kid"))
      and "phone" not in enc_envelope and "password" not in enc_envelope,
      "字段=" + ",".join(enc_envelope.keys()))
# 明文请求体
plain_body = make_login_body(DEV_PLAIN, CARD_PLAIN)
check("C4: 明文请求 wire body 含 phone/password 明文",
      "phone" in plain_body and "password" in plain_body)
# 加密登录后的 GET profile 响应 raw（直接 requests，不经客户端解密）
if mode == "off" or not c1_ok:
    skip("C4: 加密登录后 GET 响应是密文信封（data/enc/iv）",
         "off 模式不支持加密 或 C1 未成功建立会话，跳过")
else:
    prof_enc_resp = requests.get(f"{BASE_URL}/api/v1/client/account/profile",
                                 headers=c_enc._headers(), timeout=15)
    prof_enc_raw = prof_enc_resp.text
    check("C4: 加密登录后 GET 响应是密文信封（data/enc/iv）",
          PdkClient._is_envelope(prof_enc_raw), prof_enc_raw[:90])
# 明文登录后的 GET profile 响应 raw
prof_plain_raw = requests.get(f"{BASE_URL}/api/v1/client/account/profile",
                              headers=c_plain._headers(), timeout=15).text
check("C4: 明文登录后 GET 响应是明文 CommonResult（含 code，非信封）",
      '"code"' in prof_plain_raw and not PdkClient._is_envelope(prof_plain_raw), prof_plain_raw[:90])

# =====================================================================
# C5：篡改检测（信封 data 被篡改 → 42904）
# =====================================================================
line("C5【篡改检测】 信封 data 被篡改应被拒 42904")
if mode == "off":
    skip("C5: 篡改 data 后服务端拒绝（42904）", "off 模式不处理信封，加密篡改测试不适用（跳过）")
else:
    env = json.loads(c_enc._encrypt_body(json.dumps(make_login_body(DEV_ENC, CARD_ENC))))
    data_bytes = bytearray(base64.b64decode(env["data"]))
    data_bytes[-1] ^= 0xFF  # 翻转末字节，破坏 GCM 认证标签
    env["data"] = base64.b64encode(bytes(data_bytes)).decode("ascii")
    st, txt = raw_post_envelope(c_enc, env, device_id=DEV_ENC)
    code = read_response_code(txt, c1_session)
    check("C5: 篡改 data 后服务端拒绝（42904 解密失败，响应被加密返回）", code == 42904,
          f"code={code} raw={txt[:120]}")

# =====================================================================
# C6：密钥版本不匹配（信封 kid 不存在 → 42901）
# =====================================================================
line("C6【密钥版本不匹配】 信封 kid 不存在(v9) 应被拒 42901")
if mode == "off":
    skip("C6: kid=v9 不存在 → 服务端拒绝（42901）", "off 模式不处理信封，密钥版本测试不适用（跳过）")
else:
    env = json.loads(c_enc._encrypt_body(json.dumps(make_login_body(DEV_ENC, CARD_ENC))))
    env["kid"] = "v9"
    st, txt = raw_post_envelope(c_enc, env, device_id=DEV_ENC)
    code = read_response_code(txt, c1_session)
    check("C6: kid=v9 不存在 → 服务端拒绝（42901 密钥版本不匹配，响应被加密返回）",
          code == 42901, f"code={code}")

# =====================================================================
# 汇总
# =====================================================================
line("汇总")
passed = sum(1 for r in results if r)
total = len(results)
print(f"  断言通过: {passed}/{total}")
if skipped:
    print(f"  跳过(不适用): {len(skipped)} 项 —— off 模式下加密客户端专属测试(C1/C2/C5/C6)不适用")
print("  " + ("全部通过 ✅ —— 客户端通信加密行为符合设计" if passed == total
             else "存在失败 ❌ —— 见上方 FAIL"))
sys.exit(0 if passed == total else 1)
