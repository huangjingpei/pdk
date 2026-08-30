"""
PDK 多设备仿真：4 设备 + 5 设备（appId=2 / 账户 13800000000）

卡密（4 张，均已分配给该账户并生成设备许可证 UNBOUND）：
  CARD1 = PDK-36DF-0FBB-E7E3
  CARD2 = PDK-84A2-8887-4094
  CARD3 = PDK-9FB0-0A5E-AD6E
  CARD4 = PDK-9C06-3BF1-4B96

业务规则（已核对 DeviceLicenseService.authenticateAndBind）：
  - 1 张卡密 = 1 个设备许可证 = 只能绑 1 台设备（卡密被绑后，其他设备再用该卡登录 → 40383）
  - 账户下设备数量【无硬上限】，但每多一台设备就必须多一张卡
  - 设备未绑且未带卡 → 40380「当前电脑尚未绑定许可证，请输入卡密」
  - 克隆检测 40386：同一硬件指纹被「不同设备令牌」声明（跨设备碰撞）或「同一令牌下硬件剧变」触发

关于你刚才跑出的 40386（重要）：
  - 你在一台物理机上跑了 5 个“设备”，它们采集到的是【同一份真实硬件指纹】
    → 服务端算出相同的 fp_hash → 跨设备碰撞 → 全部 40386。
  - 这【不是 bug】，恰恰说明克隆检测在按预期工作：真实环境里 5 台机器各有各的硬件，
    指纹互不相同，不会碰撞。问题出在“用一台机器模拟多台设备”这个测试手法本身。
  - 此外你之前跑过 simulate_scenarios.py，库里已留有同机的旧指纹行，会让“第一台”也碰撞。
  - 修法：每个模拟设备传入【互不相同的假指纹】，模型化“5 台不同机器”；设备ID 也加 RUN 时间戳，
    避免和库里旧行撞 deviceIdHash。

场景 A（4 设备）：4 台各用 1 张卡 + 互不相同的假指纹 → 期望全部成功（4 个互相独立的许可证）
场景 B（5 设备）：
  B1：第 5 台不带卡 → 期望 40380（无空闲卡可绑）
  B2：第 5 台复用 CARD1（已绑设备1）→ 期望 40383（卡密已绑其他设备）
场景 C（克隆检测可达性）：用设备1已绑的状态，再用【差异极大的假指纹】重登 → 期望 40386（设备内指纹剧变=克隆信号）

注意：场景 A 会真实消耗 4 张卡（绑定到本 RUN 的模拟设备）。重跑前需把这 4 张卡在后台重置为 UNBOUND，
      否则会命中 40383。场景 C 复用已绑设备，不消耗额外卡。

前置自查 SQL（4 行都应存在、assigned、许可证 UNBOUND、biz_id=2）：
  SELECT c.card_key, c.status, l.status, l.biz_id
  FROM pdk_card_key c LEFT JOIN pdk_device_license l ON l.card_key_id=c.id
  WHERE c.card_key IN ('PDK-36DF-0FBB-E7E3','PDK-84A2-8887-4094','PDK-9FB0-0A5E-AD6E','PDK-9C06-3BF1-4B96');

运行：
  cd client/python
  pip install requests cryptography
  # 改下方 PWD 为账户密码、BASE_URL 指向服务器（本地默认 http://localhost:8080 即可）
  python simulate_4_5_devices.py
"""
import sys
from datetime import datetime

sys.path.insert(0, ".")
from pdk_client import PdkClient, PdkClientError

BASE_URL = "http://localhost:8080"   # ← 指向你的服务器（本地默认即可）
APP_ID = 2
PHONE = "13800000000"               # ← 若“改用户”换了账户，改这里
PWD = "13800000000"                 # ← 必填：账户登录密码

CARDS = [
    "PDK-36DF-0FBB-E7E3",
    "PDK-84A2-8887-4094",
    "PDK-9FB0-0A5E-AD6E",
    "PDK-9C06-3BF1-4B96",
]

# RUN 时间戳：保证每次运行的设备ID与假指纹全局唯一，避免和库里旧行撞车
RUN = datetime.now().strftime("%Y%m%d%H%M%S")
DEVS = [f"SIM-{RUN}-PC-{i + 1:03d}" for i in range(5)]   # 5 个模拟设备ID


def line(t):
    print("\n" + "=" * 66 + f"\n{t}\n" + "=" * 66)


def fake_fp(i: int, run: str = RUN) -> dict:
    """生成第 i 台模拟设备的【唯一假硬件指纹】，模型化一台独立物理机器。
    每台互不相同 → 服务端算出不同的 fp_hash → 不会触发跨设备克隆碰撞。"""
    return {
        "motherboardSerial": f"MB-{run}-{i:03d}-ABCD1234",
        "diskSerial": f"DISK-{run}-{i:03d}-EFGH5678",
        "cpuid": f"CPU-{run}-{i:03d}-IJKL9012",
    }


def login_as(dev, card=None, fingerprint=None):
    c = PdkClient(BASE_URL, app_id=APP_ID, phone=PHONE, device_id=dev, use_crypto=False)
    try:
        r = c.login(password=PWD, card_key=card, fingerprint=fingerprint)
        lic = r.get("deviceLicense") or {}
        print(f"  [登录成功] dev={dev} card={card or '(无)'} "
              f"许可证状态={lic.get('status')} id={lic.get('licenseId')}")
        return c, r
    except PdkClientError as e:
        print(f"  [登录被拒] dev={dev} card={card or '(无)'} -> [{e.code}] {e.message}")
        return None, e


def expect(desc, err, want):
    if not isinstance(err, PdkClientError):
        print(f"  [断言] {desc}: 实际=成功(非预期错误) 期望 {want} -> FAIL")
        return
    ok = err.code in want
    print(f"  [断言] {desc}: 实际 [{err.code}] 期望 {want} -> {'PASS' if ok else 'FAIL'}")


# =====================================================================
# 场景 A：4 台设备，各用 1 张卡密 + 互不相同的假指纹登录（激活/绑定）
# =====================================================================
line("场景A【4 设备】 4 台各用 1 张卡密 + 各不相同假指纹登录（模型化 4 台独立机器）")
for i in range(4):
    login_as(DEVS[i], CARDS[i], fingerprint=fake_fp(i))

# =====================================================================
# 场景 B1：第 5 台不带卡 → 应 40380
# =====================================================================
line("场景B1【5 设备】 第5台不带卡密登录 -> 应 40380")
_, e5a = login_as(DEVS[4], None)
expect("第5台无卡密无法登录", e5a, [40380])

# =====================================================================
# 场景 B2：第 5 台复用 CARD1（已绑设备1）登录 → 应 40383
# =====================================================================
line("场景B2【5 设备】 第5台复用 CARD1（已绑 SIM 设备1）登录 -> 应 40383")
_, e5b = login_as(DEVS[4], CARDS[0])
expect("第5台复用已绑定卡密被拒", e5b, [40383])

# =====================================================================
# 场景 C：克隆检测可达性 —— 用已绑设备1，再用【差异极大的假指纹】重登
#           → 设备内指纹剧变（similarity<0.5）→ 应 40386
# （复用已绑设备，不消耗额外卡；演示“克隆检测确实在生效”）
# =====================================================================
line("场景C【克隆检测】 已绑设备1 用差异极大的假指纹重登 -> 应 40386（设备内指纹剧变）")
_, eC = login_as(DEVS[0], None, fingerprint={
    "motherboardSerial": "CLONED-MOTHERBOARD-00000000",
    "diskSerial": "CLONED-DISK-00000000",
    "cpuid": "CLONED-CPU-00000000",
})
expect("设备内指纹剧变触发克隆检测", eC, [40386])

print("\n仿真结束。场景 A 全成功 + B1/B2/C 断言 PASS 即表示「一卡一设备」与「克隆检测」均生效。")
print("提示：场景 A 已真实消耗 4 张卡；重跑前请先在后台把 4 张卡重置为 UNBOUND。")
