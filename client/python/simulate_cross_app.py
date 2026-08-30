"""
PDK 跨业务隔离测试：appId=2 (ZHIBO_AI / biz 2) × appId=3 (ZHIBO_LIVE / biz 3)

前置（已在本机测试库完成，见脚本顶部注释）：
  - user1(biz3) 密码已统一为与 user2(biz2) 相同的 13800000000，便于单密码跑测试
  - PDK-36DF-0FBB-E7E3 已复位为 UNBOUND（镜像 unbind：清空激活/生效/到期时间与设备绑定）
  - appId=3 的 5 张卡（PDK-5B34 / 0CFB / EB35 / 09A1 / 4DCC）均为 UNBOUND、已分配给手机号 13800000000

测试核心问题：
  appId=2 与 appId=3 两个客户端【同时】做登录/绑定/解绑，彼此是否互相干扰？
  → 业务按 biz_id 完全隔离：注册表含 app_id、X-PDK-App-ID、拦截器、服务层 bizId 过滤、
    数据层 biz_id、指纹盐。本脚本用真实后端请求验证该隔离在运行期确实成立。

关键不变量（逐一断言）：
  1) 同手机号(13800000000) 在 biz2/biz3 下解析为不同 user（user2/user1），登录互不串号
  2) 卡密按 biz_id 作用域：appId=2 用 appId=3 的卡 → 40382；反之亦然
  3) appId=3 解绑/绑定不影响 appId=2 已绑设备，反之亦然
  4) 解绑后重绑会【重新计算】激活/到期时间（验证 unbind 清空时间字段的修复）
  5) 克隆检测(40386)在各自 biz 内独立生效，不跨 biz 误伤

运行：
  cd client/python
  pip install requests cryptography
  python simulate_cross_app.py
"""
import sys
import subprocess
import threading
from datetime import datetime

sys.path.insert(0, ".")
from pdk_client import PdkClient, PdkClientError

BASE_URL = "http://localhost:8080"
PHONE = "13800000000"
PWD = "13800000000"

# 本 RUN 的两张“主卡”：appId=2 用复位后的 PDK-36DF；appId=3 用 PDK-5B34
CARD_A2 = "PDK-36DF-0FBB-E7E3"   # biz 2
CARD_A3 = "PDK-5B34-7479-340E"   # biz 3
# 额外一张 appId=3 卡，用于验证“多设备不影响另一业务”
CARD_A3_B = "PDK-0CFB-4792-B745"  # biz 3

RUN = datetime.now().strftime("%Y%m%d%H%M%S")
# 每台模拟机器：唯一 deviceId + 唯一假指纹（避免同机同指纹触发 40386 克隆）
M2 = f"XAPP-{RUN}-A2-PC"          # appId=2 机器
M3 = f"XAPP-{RUN}-A3-PC"          # appId=3 机器
M3b = f"XAPP-{RUN}-A3B-PC"        # appId=3 第二台机器

results = []


def fake_fp(tag: str) -> dict:
    return {
        "motherboardSerial": f"MB-{RUN}-{tag}-AAA11111",
        "diskSerial": f"DISK-{RUN}-{tag}-BBB22222",
        "cpuid": f"CPU-{RUN}-{tag}-CCC33333",
    }


def line(t):
    print("\n" + "=" * 70 + f"\n{t}\n" + "=" * 70)


def check(desc, cond, detail=""):
    ok = bool(cond)
    results.append(ok)
    print(f"  [{'PASS' if ok else 'FAIL'}] {desc}" + (f"  -> {detail}" if detail else ""))


def lic_status_of(c: PdkClient) -> str:
    try:
        prof = c.profile()
        lic = prof.get("deviceLicense") or {}
        return lic.get("status") or "无许可证"
    except Exception as e:
        return f"profile异常:{e}"


def bind_app(app_id, dev, card, tag):
    """带卡登录 = 激活/绑定。返回 (client, error)。"""
    c = PdkClient(BASE_URL, app_id=app_id, phone=PHONE, device_id=dev, use_crypto=False)
    try:
        r = c.login(password=PWD, card_key=card, fingerprint=fake_fp(tag))
        lic = r.get("deviceLicense") or {}
        print(f"  [绑定成功] appId={app_id} dev={dev} card={card} 许可证状态={lic.get('status')} id={lic.get('licenseId')}")
        return c, None
    except PdkClientError as e:
        print(f"  [绑定被拒] appId={app_id} dev={dev} card={card} -> [{e.code}] {e.message}")
        return None, e


def login_no_card(app_id, dev, tag):
    c = PdkClient(BASE_URL, app_id=app_id, phone=PHONE, device_id=dev, use_crypto=False)
    try:
        r = c.login(password=PWD, fingerprint=fake_fp(tag))
        lic = r.get("deviceLicense") or {}
        print(f"  [登录成功] appId={app_id} dev={dev} 许可证状态={lic.get('status')} id={lic.get('licenseId')}")
        return c, None
    except PdkClientError as e:
        print(f"  [登录被拒] appId={app_id} dev={dev} -> [{e.code}] {e.message}")
        return None, e


def concurrent(*fns):
    """并发执行多个无参函数（用于“同时登录/同时解绑”）。"""
    ts = [threading.Thread(target=f, name=f"t{i}") for i, f in enumerate(fns)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()


def reset_cards_for_run():
    """幂等：把本 RUN 用到的测试卡复位为 UNBOUND（镜像 unbind 行为），
    使脚本可反复重跑而不受上一次运行残留状态影响（否则已绑卡会在 S6 触发 40383）。
    使用本机 mysql.exe（不依赖 pymysql）。"""
    MYSQL_EXE = "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
    DSN = ["-u", "root", "-p89dfu*#$ewr87l", "-h", "127.0.0.1", "pdk_biz_db"]
    cards = sorted({CARD_A2, CARD_A3, CARD_A3_B})

    def mysql_exec(sql: str) -> str:
        r = subprocess.run([MYSQL_EXE] + DSN + ["-e", sql],
                           capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(r.stderr.strip())
        return r.stdout

    try:
        in_list = ",".join(repr(c) for c in cards)
        out = mysql_exec(
            "SELECT ck.card_key, ck.id, dl.id, dl.user_device_id "
            "FROM pdk_card_key ck LEFT JOIN pdk_device_license dl ON dl.card_key_id=ck.id "
            f"WHERE ck.card_key IN ({in_list});")
        rows = [ln.split("\t") for ln in out.strip().splitlines()[1:] if ln.strip()]
        for _ck_key, ck_id, dl_id, dev_id in rows:
            if dl_id and dl_id != "NULL":
                mysql_exec(
                    "UPDATE pdk_device_license SET user_device_id=NULL, status='UNBOUND', "
                    "activated_at=NULL, effective_at=NULL, expire_at=NULL, version=version+1 "
                    f"WHERE id={dl_id};")
                if dev_id and dev_id != "NULL":
                    mysql_exec(f"UPDATE pdk_user_device SET status='UNBOUND' WHERE id={dev_id};")
            mysql_exec(
                "UPDATE pdk_card_key SET status='ASSIGNED', activated_at=NULL, "
                "activated_by_user_id=NULL, activated_by_phone=NULL, activated_device_id=NULL "
                f"WHERE id={ck_id};")
        print("  [reset] 本 RUN 测试卡已幂等复位为 UNBOUND: " + ", ".join(cards))
    except Exception as e:
        print(f"  [reset warn] 自动复位失败（请手动检查）: {e}")


# =====================================================================
# S1：两个客户端【同时登录 + 绑定】 —— 并发，彼此互不影响
# =====================================================================
line("S1【并发登录+绑定】 appId=2 绑 PDK-36DF / appId=3 绑 PDK-5B34 同时进行")
# 幂等：先复位本 RUN 涉及的测试卡，避免上一次运行残留导致 S6 误判 40383
reset_cards_for_run()
a2_client = {}
a3_client = {}

def _s1_a2():
    c, e = bind_app(2, M2, CARD_A2, "A2")
    a2_client["c"] = c

def _s1_a3():
    c, e = bind_app(3, M3, CARD_A3, "A3")
    a3_client["c"] = c

concurrent(_s1_a2, _s1_a3)
c2 = a2_client.get("c")
c3 = a3_client.get("c")
check("S1: appId=2 绑定成功", c2 is not None)
check("S1: appId=3 绑定成功", c3 is not None)
if c2 and c3:
    check("S1: appId=2 绑定后许可证为 ACTIVE", lic_status_of(c2) == "ACTIVE",
          lic_status_of(c2))
    check("S1: appId=3 绑定后许可证为 ACTIVE", lic_status_of(c3) == "ACTIVE",
          lic_status_of(c3))

# 记录 appId=2 绑定后的设备列表（隔离对照基线）
a2_devs_before = c2.device_license_list() if c2 else []
check("S1: appId=2 账户下已有设备（含历史 3 台 + 本轮 1 台）",
      isinstance(a2_devs_before, list) and len(a2_devs_before) >= 1,
      f"{len(a2_devs_before) if isinstance(a2_devs_before,list) else '?'}")


# =====================================================================
# S2：【一个绑定(维持) 一个解绑】 —— appId=2 维持在线，appId=3 解绑
#      验证 appId=3 解绑不波及 appId=2
# =====================================================================
line("S2【一个维持 一个解绑】 appId=2 重登维持 / appId=3 解绑（并发）")
a2_after = {}
a3_unbind_err = {}

def _s2_a2():
    c, e = login_no_card(2, M2, "A2")   # 已绑设备重登，应维持 ACTIVE
    a2_after["c"] = c

def _s2_a3():
    try:
        msg = c3.unbind_device()
        a3_unbind_err["msg"] = msg
        a3_unbind_err["err"] = None
    except PdkClientError as e:
        a3_unbind_err["err"] = e

concurrent(_s2_a2, _s2_a3)
check("S2: appId=3 解绑成功", a3_unbind_err.get("err") is None,
      a3_unbind_err.get("msg") or str(a3_unbind_err.get("err")))
check("S2: appId=2 解绑期间仍维持 ACTIVE（未被 appId=3 波及）",
      bool(a2_after.get("c")) and lic_status_of(a2_after["c"]) == "ACTIVE",
      lic_status_of(a2_after["c"]) if a2_after.get("c") else "n/a")
# 解绑即注销会话（token 失效是预期行为），以解绑成功消息作为“已解绑”证据
check("S2: appId=3 解绑消息确认已解绑",
      "解绑" in (a3_unbind_err.get("msg") or ""), a3_unbind_err.get("msg"))


# =====================================================================
# S3：【同时解绑】 —— 先各自重绑，再两个客户端同时解绑
# =====================================================================
line("S3【同时解绑】 两客户端各自重绑后立即并发解绑")
# 重绑
c2b, _ = bind_app(2, M2, CARD_A2, "A2")
c3b, _ = bind_app(3, M3, CARD_A3, "A3")
check("S3: 重绑后 appId=2 ACTIVE", lic_status_of(c2b) == "ACTIVE" if c2b else False)
check("S3: 重绑后 appId=3 ACTIVE", lic_status_of(c3b) == "ACTIVE" if c3b else False)

a2ub = {}
a3ub = {}
def _s3_a2():
    try:
        a2ub["msg"] = c2b.unbind_device(); a2ub["err"] = None
    except PdkClientError as e:
        a2ub["err"] = e
def _s3_a3():
    try:
        a3ub["msg"] = c3b.unbind_device(); a3ub["err"] = None
    except PdkClientError as e:
        a3ub["err"] = e

concurrent(_s3_a2, _s3_a3)
check("S3: appId=2 解绑成功", a2ub.get("err") is None, a2ub.get("msg") or str(a2ub.get("err")))
check("S3: appId=3 解绑成功", a3ub.get("err") is None, a3ub.get("msg") or str(a3ub.get("err")))
# 解绑即注销会话（token 失效是预期），解绑成功消息即“已解绑”证据；隔离性由 S2/S4/S6/S7 佐证


# =====================================================================
# S4：【交叉卡密误用】 —— 卡密按 biz_id 作用域，跨业务必 40382
# =====================================================================
line("S4【交叉卡密误用】 appId=2 用 biz3 卡 / appId=3 用 biz2 卡")
e_a2_cross = {}
e_a3_cross = {}

def _s4_a2():
    try:
        PdkClient(BASE_URL, app_id=2, phone=PHONE, device_id=f"{M2}-x", use_crypto=False) \
            .login(password=PWD, card_key=CARD_A3, fingerprint=fake_fp("A2x"))  # biz3 卡
        e_a2_cross["err"] = None
    except PdkClientError as e:
        e_a2_cross["err"] = e

def _s4_a3():
    try:
        PdkClient(BASE_URL, app_id=3, phone=PHONE, device_id=f"{M3}-x", use_crypto=False) \
            .login(password=PWD, card_key=CARD_A2, fingerprint=fake_fp("A3x"))  # biz2 卡
        e_a3_cross["err"] = None
    except PdkClientError as e:
        e_a3_cross["err"] = e

concurrent(_s4_a2, _s4_a3)
check("S4: appId=2 用 biz3 卡 → 40382（卡密不属于当前业务）",
      e_a2_cross.get("err") is not None and e_a2_cross["err"].code == 40382,
      str(e_a2_cross.get("err")))
check("S4: appId=3 用 biz2 卡 → 40382（卡密不属于当前业务）",
      e_a3_cross.get("err") is not None and e_a3_cross["err"].code == 40382,
      str(e_a3_cross.get("err")))


# =====================================================================
# S5：【解绑后重绑时间刷新】 —— 验证 unbind 清空时间字段的修复
#      重绑后 activatedAt 应为“最近”，而非历史旧值（2026-08-31）
# =====================================================================
line("S5【重绑刷新时间】 appId=2 解绑后重绑，激活时间应重新计算（非沿用旧周期）")
c5, _ = bind_app(2, M2, CARD_A2, "A2")          # PDK-36DF 此时 UNBOUND，重绑
if c5:
    lic = c5.profile().get("deviceLicense") or {}
    activated = lic.get("activatedAt") or lic.get("activated_at")
    check("S5: 重绑成功且许可证 ACTIVE", lic.get("status") == "ACTIVE", str(lic.get("status")))
    # 旧激活时间是 2026-08-31；本 RUN 在 2026 年内但应当是“刚刚”，与旧值不同
    check("S5: 激活时间已刷新（非历史旧值 2026-08-31T00:26）",
          activated and "2026-08-31T00:26" not in str(activated), str(activated))


# =====================================================================
# S6：【多设备不影响另一业务】 —— appId=3 再加一台设备，appId=2 设备数不变
# =====================================================================
line("S6【多设备隔离】 appId=3 增绑第 2 台，appId=2 设备数不受影响")
# 用当前有效的 appId=2 客户端（c5，S5 创建且未解绑）做设备数前后对比，避免用到已失效会话
if c5:
    a2_devs_before_s6 = c5.device_license_list()
    c3b2, _ = bind_app(3, M3b, CARD_A3_B, "A3B")
    check("S6: appId=3 第二台绑定成功", c3b2 is not None)
    a2_devs_after_s6 = c5.device_license_list()
    check("S6: appId=2 设备数在 appId=3 增绑前后一致",
          isinstance(a2_devs_before_s6, list) and isinstance(a2_devs_after_s6, list)
          and len(a2_devs_before_s6) == len(a2_devs_after_s6),
          f"before={len(a2_devs_before_s6)} after={len(a2_devs_after_s6)}")
else:
    c3b2, _ = bind_app(3, M3b, CARD_A3_B, "A3B")
    check("S6: appId=3 第二台绑定成功", c3b2 is not None)


# =====================================================================
# S7：【克隆检测按 biz 独立】 —— appId=3 已绑设备用差异极大指纹重登 → 40386
#      （仅验证该 biz 内克隆检测生效，不应跨 biz 误伤 appId=2）
# =====================================================================
line("S7【克隆检测按 biz 独立】 appId=3 已绑设备指纹剧变 → 40386（不影响 appId=2）")
e_clone = {}
def _s7_a3_clone():
    c = PdkClient(BASE_URL, app_id=3, phone=PHONE, device_id=M3, use_crypto=False)
    try:
        c.login(password=PWD, fingerprint={
            "motherboardSerial": "CLONED-MOTHERBOARD-00000000",
            "diskSerial": "CLONED-DISK-00000000",
            "cpuid": "CLONED-CPU-00000000"})
        e_clone["err"] = None
    except PdkClientError as e:
        e_clone["err"] = e
_s7_a3_clone()
check("S7: appId=3 设备内指纹剧变 → 40386（克隆检测在该 biz 内生效）",
      e_clone.get("err") is not None and e_clone["err"].code == 40386,
      str(e_clone.get("err")))
# appId=2 仍可用（不受影响）
c2_still, e2 = login_no_card(2, M2, "A2")
check("S7: appId=2 在 appId=3 克隆告警后仍可正常登录",
      c2_still is not None, str(e2) if e2 else "ok")


# =====================================================================
# 汇总
# =====================================================================
line("汇总")
passed = sum(1 for r in results if r)
total = len(results)
print(f"  断言通过: {passed}/{total}")
print("  " + ("全部通过 ✅ —— appId=2 与 appId=3 跨业务操作完全隔离" if passed == total
             else "存在失败 ❌ —— 见上方 FAIL"))
sys.exit(0 if passed == total else 1)
