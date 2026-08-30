"""
PDK 客户端业务仿真脚本（直播AI appId=2 / 账户 13800000000）

对应需求里的 5 个场景：
  1. 登录      —— 已绑定设备再次登录（可不带卡密）
  2. 绑定      —— 新设备带卡密登录（激活/绑定）
  3. 解绑      —— 释放设备，有效期继续计算
  4. 其它电脑已绑定该卡密 → 登录应被拒(40383)；解绑后可换机
  5. 同设备换卡 —— 先用卡密1登录，再用卡密2登录（必须先解绑卡密1，否则卡密2被忽略）

前置条件（运行前必须为真，否则会命中对应错误码）：
  - 账户 13800000000 在 appId=2 对应业务下已存在，且设置了密码
  - 业务 appId=2 的授权模式为 DEVICE_LICENSE（usesDeviceLicense=true）
  - 两条卡密 PDK-9C06-3BF1-4B96 / PDK-9FB0-0A5E-AD6E 都已分配给 13800000000，
    且各自已生成设备许可证（状态 UNBOUND）。若只发卡未生成许可证，登录会报 40382。

运行：
  cd client/python
  pip install requests cryptography
  # 修改下方 PWD 为 13800000000 的实际密码、BASE_URL 指向你的服务器
  python simulate_scenarios.py
"""
import sys
sys.path.insert(0, ".")
from pdk_client import PdkClient, PdkClientError

BASE_URL = "http://localhost:8080"          # ← 改成你的服务器地址
APP_ID = 2
PHONE = "13800000000"
PWD = "13800000000"             # ← 必填
CARD1 = "PDK-9C06-3BF1-4B96"
CARD2 = "PDK-9FB0-0A5E-AD6E"

# 用两个固定 deviceId 模拟两台电脑（同一台机器上注册表只会存一个 deviceId，故显式指定）
DEV_A = "SIM-PC-A-001"
DEV_B = "SIM-PC-B-002"


def line(t):
    print("\n" + "=" * 64 + f"\n{t}\n" + "=" * 64)


def login_as(dev, card=None):
    c = PdkClient(BASE_URL, app_id=APP_ID, phone=PHONE, device_id=dev, use_crypto=False)
    try:
        r = c.login(password=PWD, card_key=card)
        lic = (r.get("deviceLicense") or {})
        print(f"  [登录成功] dev={dev} card={card or '(无)'} "
              f"许可证状态={lic.get('status')} 指纹哈希={'有' if r.get('fingerprintHash') else '无'}")
        return c, r
    except PdkClientError as e:
        print(f"  [登录被拒] dev={dev} card={card or '(无)'} -> [{e.code}] {e.message}")
        return None, e


def expect(desc, actual_code, want):
    ok = actual_code in want
    print(f"  [断言] {desc}: 实际 {actual_code} 期望 {want} -> {'PASS' if ok else 'FAIL'}")


# =====================================================================
# 场景2：绑定 —— PC-A 首次带卡密1登录（激活/绑定设备）
# =====================================================================
line("场景2【绑定】 PC-A 首次带卡密1登录（激活/绑定）")
ca, _ = login_as(DEV_A, CARD1)

# =====================================================================
# 场景1：登录 —— PC-A 已绑定后再次登录（可不带卡密）
# =====================================================================
line("场景1【登录】 PC-A 已绑定后再次登录（不带卡密）")
login_as(DEV_A, None)

# =====================================================================
# 场景4：其它电脑已绑定该卡密 → 登录应被拒(40383)；解绑后可换机
# =====================================================================
line("场景4【其它电脑】 PC-B 用已被 PC-A 绑定的卡密1登录 -> 应拒 40383")
_, err = login_as(DEV_B, CARD1)
if isinstance(err, PdkClientError):
    expect("卡密1已在PC-A绑定，PC-B登录被拒", err.code, [40383])

line("场景4【续】 PC-A 解绑后，PC-B 可用卡密1登录（换机成功）")
if ca:
    try:
        print("  PC-A 解绑:", ca.unbind_device())
    except PdkClientError as e:
        print(f"  PC-A 解绑异常 [{e.code}] {e.message}")
login_as(DEV_B, CARD1)

# =====================================================================
# 场景3：解绑 —— PC-B 主动解绑卡密1，原会话应失效
# =====================================================================
line("场景3【解绑】 PC-B 主动解绑卡密1")
cb, _ = login_as(DEV_B, CARD1)
if cb:
    try:
        print("  PC-B 解绑:", cb.unbind_device())
    except PdkClientError as e:
        print(f"  PC-B 解绑异常 [{e.code}] {e.message}")
    try:
        cb.profile()
        print("  [异常] 解绑后仍能调用受保护接口（不符合预期）")
    except PdkClientError as e:
        expect("解绑后原会话失效", e.code, [40103, 40106, 40385, 40381])

# =====================================================================
# 场景5：同设备换卡 —— 先用卡密1登录，再用卡密2登录
#   关键点：同一设备同时只能绑一张卡；不先解绑时卡密2会被忽略
# =====================================================================
line("场景5【换卡-准备】 PC-A 重新用卡密1绑定（恢复 CARD1↔PC-A）")
login_as(DEV_A, CARD1)

line("场景5【换卡-坑】 PC-A 不先解绑，直接用卡密2登录 -> 卡密2被忽略，仍返回卡密1许可证")
c5, r5 = login_as(DEV_A, CARD2)
if isinstance(r5, dict):
    lic = r5.get("deviceLicense") or {}
    print(f"  → 用卡密2登录，但返回的许可证: {lic}")
    print("  （说明：未先解绑时系统返回已绑定的卡密1许可证，卡密2被忽略，不报错）")

line("场景5【换卡-正确】 PC-A 先解绑卡密1，再用卡密2登录 -> 成功绑定卡密2")
ca2, _ = login_as(DEV_A, CARD1)
if ca2:
    try:
        ca2.unbind_device()
    except PdkClientError as e:
        print(f"  PC-A 解绑异常 [{e.code}] {e.message}")
login_as(DEV_A, CARD2)

print("\n仿真结束。以上每个 [断言] 输出 PASS 即表示对应设计已覆盖。")
