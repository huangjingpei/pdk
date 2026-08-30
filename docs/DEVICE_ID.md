# PDK 设备标识（Device ID / UUID）设计与对接文档

> 适用范围：client-pyqt 桌面校验器、后端客户端鉴权链路（client StpLogic）。
> 文档版本：2026-08-21 — 对应本地提交前的实现（服务端权威 + 本地缓存引导，方案 A）。

---

## 1. 概述与目标

设备标识（`device_id`）是客户端侧用于**单设备互踢**和**账号级稳定识别**的标识。
本系统采用 **「服务端权威 + 客户端本地缓存引导」** 的方案（方案 A），目标如下：

| 目标 | 说明 |
|---|---|
| 唯一性 | 不同安装实例 / 不同物理机的设备 ID 互不冲突 |
| 稳定性 | 同一账号绑定的设备 ID **永不漂移**（不依赖 MAC / 主机名等易变信号） |
| 无死锁 | 客户端**不能**完全不存 ID，否则「要先登录才能取 ID、又要 ID 才能登录」会死锁 |
| 服务端权威 | 真正的设备 ID 由后端 `user.device_id` 决定，客户端跟随 |
| 解绑兼容 | 解绑设备后，后续登录仍复用同一设备 ID |

> ⚠️ 关于「纯不存本地」的可行性结论：**不可行**。详见 §4.2。

---

## 2. 整体架构

```
┌──────────────────────────┐         HTTPS + X-PDK-Device-ID 头         ┌──────────────────────────┐
│     client-pyqt (桌面)    │ ───────────────────────────────────────▶ │        后端 (Spring)        │
│                          │                                          │                          │
│  default_device_id():    │                                          │  user.device_id (DB 权威) │
│   ① 环境变量 PDK_DEVICE_ │                                          │  Redis: pdk:device:bind:  │
│      ID (最高优先级)      │ ◀──── 登录/注册响应回传 deviceId ──────── │         {phone}  (30min TTL) │
│   ② 本地缓存文件          │      + payload() 始终携带 deviceId        │                          │
│  %ProgramData%\PDK\{app_id}\device_id  │                                          │  DeviceSecurityInterceptor│
│       device_id（纯文本）     │                                          │  强制校验 device_id 匹配   │
│   ③ 本机指纹种子兜底      │                                          │  → 不匹配返回 40103        │
└──────────────────────────┘                                          └──────────────────────────┘
```

**数据流要点：**
- 服务端 `user.device_id` 是**唯一真相源**；
- 客户端本地文件**只是引导缓存**（让首次登录请求就带上正确的设备头，避免死锁）；
- 任何一次成功登录 / 注册，服务端都会把权威 `deviceId` 写回客户端本地缓存。

---

## 3. 实现方式

### 3.1 后端实现

#### 3.1.1 数据模型
- `user.device_id` 列：账号级稳定标识，注册时写入、首次登录补绑。
- 活跃会话缓存：`DeviceBindingService` 维护 Redis 键 `pdk:device:bind:{phone}`，TTL 30 分钟。

#### 3.1.2 关键接口语义（`ClientAuthController.java`）

| 接口 | 行为 |
|---|---|
| `POST /register` | 写入 `user.device_id = dto.deviceId`，并 `bind(phone, deviceId)`；响应 payload 回传 `deviceId`。 |
| `POST /login` | 若 `user.device_id` 已存在且与入参不一致 → **40103 互踢**；若为 null → 首次登录补绑；并 `bind()`；响应回传 `deviceId`。 |
| `POST /unbind-device` | **仅**清 Redis 活跃会话 + 强制登出（`clientStpLogic.logout()`）；**保留** `user.device_id`（不再置空）。响应：`已解绑当前会话，账号设备标识保持不变`。 |

> 对应源码位置：
> - 注册写入 `ClientAuthController.java:72`、绑定 `:92`
> - 登录校验 `:119`（不一致 40103）、首次补绑 `:122-123`、绑定 `:128`
> - 解绑保留语义 `:158-166`、payload 回传 `:173`

#### 3.1.3 设备安全拦截（`DeviceSecurityInterceptor.java`）
- 对**除登录 / 注册等白名单**外的所有客户端接口，从请求头 `X-PDK-Device-ID` 取值；
- 与账号绑定的 `device_id` 比对，不一致 → 返回 **`40103 DEVICE_MISMATCH`**（互踢 / 设备不匹配）；
- 登录接口可「未绑定则绑定」，因此首个设备总能登录成功。

### 3.2 客户端实现（`client-pyqt/pdk_client.py`）

| 函数 / 方法 | 作用 |
|---|---|
| `_fingerprint_device_id()` | 兜底种子：`SHA256(主机名:MAC:操作系统)` 取前 24 位，前缀 `PYQT-`。仅在无缓存时落地，保证既有绑定账号兼容。 |
| `load_device_id(app_id)` | 读取本地缓存：Windows 为 `%ProgramData%\PDK\{app_id}\device_id`，其他为 `~/.pdk_client/{app_id}/device_id`（纯文本）；失败返回 `""`。 |
| `save_device_id(did, app_id)` | 写回本地缓存（同路径，纯文本，不再含 `updated_at` JSON）。 |
| `default_device_id(app_id)` | 优先级：① `PDK_DEVICE_ID` 环境变量 → ② 本地缓存（按 app_id 隔离）→ ③ 指纹种子落地。 |
| `register()` / `login()` | 成功后以**服务端返回的 `deviceId`** 覆盖会话与本地缓存（服务端权威）。 |
| `unbind_device()` | **仅清登录态 token，保留 `session.device_id`**（与后端不再置空语义一致）。 |

**关键代码片段（服务端权威写回）：**
```python
# login / register 成功后
server_did = (data.get("deviceId") or device_id)   # 服务端优先
self.session.device_id = server_did
save_device_id(server_did)                          # 覆盖本地缓存
```

#### 3.2.1 GUI 同步（`client-pyqt/main.py`）
登录成功后，「设备 ID」输入框会自动同步为**服务端权威值**，界面可见「服务端说了算」。

---

## 4. 注意事项

### 4.1 本地缓存文件的作用（务必理解）
`%ProgramData%\PDK\{app_id}\device_id`（Windows）或 `~/.pdk_client/{app_id}/device_id`（其他）**不是权威**，只是「首请求引导」。没有它，应用重启后就没有 device_id → 登录请求缺头 → 被 40103 拒绝 → 而想拿服务端值又得先登录 → **死锁**。所以本地缓存必须存在，但它随时会被服务端返回值覆盖。

### 4.2 为什么不能「完全不存本地」
纯服务端方案需新增「恢复登录」接口（凭手机号+密码+短信，**不校验** device_id）来打破死锁。代价是放松单设备互踢（凭据即设备）。本系统选方案 A 即为此取舍。

### 4.3 设备 ID 自报的固有局限
`X-PDK-Device-ID` 是客户端**自报**请求头，服务端无法阻止伪造。这是客户端侧设备标识的共性限制。若需强设备绑定，应在后端引入设备 attestation / 绑定审批流（超出本方案范围）。

### 4.4 单设备互踢语义
- 同一账号在**不同 device_id** 上登录 → 后者登录成功、前者后续请求 40103（互踢）。
- 同一 device_id 重复登录 → 视为同一设备，不互踢。

### 4.5 解绑语义变更（破坏性变更，请注意）
**旧语义**：解绑会把 `user.device_id` 置空；**新语义（方案 A）**：解绑**保留** `user.device_id`，仅释放当前会话。因此：
- 解绑后**下次登录复用同一 device_id**，无需重新绑定；
- 若你期望「解绑=彻底换设备」，需另行在后端清空 `user.device_id`（当前不推荐，会与本方案冲突）。

### 4.6 同机多安装实例
- 默认情况下，同机多个 client-pyqt 共享同一本地缓存文件 → **会撞 ID**；
- 解决：为每个实例设置不同的 `PDK_DEVICE_ID` 环境变量（如 `PYQT-INSTANCE-B`），互不冲突。

### 4.7 后端需重启
后端 `unbind-device` 新语义需要**重启后端**才能生效；前端 / PyQt 为热更新。

---

## 5. 问题排查

| 现象 | 根因 | 解决方案 |
|---|---|---|
| 登录返回 `40103 DEVICE_MISMATCH` | 当前 device_id 与 `user.device_id` 不匹配（换机 / 清库 / ID 漂移） | 1) 确认本地缓存 `~/.pdk_client/device_id.json` 与服务器一致；2) 解绑后重新登录（会自动复用）；3) 删除缓存让首次登录重新绑定 |
| 应用重启后突然 40103 | 本地缓存被删 + 当前登录设备与历史绑定不一致 | 重新登录一次即可，登录响应会把正确 deviceId 写回 |
| 同机跑两个客户端互踢 | 共享同一本地缓存 → 同一 device_id | 给其中一个实例设 `PDK_DEVICE_ID` 环境变量区分 |
| acquire-token 报设备不匹配 | `X-PDK-Device-ID` 与账号绑定不一致（同上 40103） | 同 40103 排查 |
| 解绑后再登录设备变了 | 误以为解绑清空 device_id（实际方案 A 保留） | 确认后端已应用新语义；若仍异常，检查后端是否重启 |
| 请求头看不到 device_id | 未登录 / session 未初始化 | 先登录，再调用需鉴权的接口 |

**排查命令（定位当前绑定）：**
```sql
-- 后端 MySQL（pdk_biz_db）
SELECT phone, device_id, status FROM pdk_user WHERE phone = '138xxxx';
```
```bash
# 客户端本地缓存
cat %ProgramData%\PDK\{app_id}\device_id   # Windows；其他系统： cat ~/.pdk_client/{app_id}/device_id
```

---

## 6. 自测

### 6.1 单元 / 集成自测（`pdk_client.py`）

```bash
cd E:/pdk/client-pyqt
python - <<'PY'
import os, json, importlib
cfg = os.path.join(os.environ.get("ProgramData", os.path.expanduser("~")), "PDK", str(app_id), "device_id")  # Windows 走 ProgramData；其他走 ~/.pdk_client/{app_id}
if os.path.exists(cfg): os.remove(cfg)

import pdk_client as C

# 1) 首次：无缓存 -> 指纹种子落地
id1 = C.default_device_id(1); assert os.path.exists(cfg)

# 2) 服务端权威写回
C.save_device_id("PYQT-SERVER-AUTHORITATIVE-ABCD1234", 1)
assert C.default_device_id(1) == "PYQT-SERVER-AUTHORITATIVE-ABCD1234"

# 3) 重启复用（reload 模拟）
importlib.reload(C)
assert C.default_device_id(1) == "PYQT-SERVER-AUTHORITATIVE-ABCD1234"

# 4) login 成功采用服务端 deviceId 并写回
client = C.PdkApiClient()
client.request = lambda *a, **k: {"code":200,"message":"ok",
    "data":{"tokenName":"satoken","tokenValue":"tok","phone":"13800000000",
            "deviceId":"PYQT-SERVER-AUTHORITATIVE-ABCD1234","status":"TRIAL"}}
client.login("13800000000","Passw0rd!233","PYQT-CLIENT-SENT")
assert client.session.device_id == "PYQT-SERVER-AUTHORITATIVE-ABCD1234"
assert C.load_device_id(1) == "PYQT-SERVER-AUTHORITATIVE-ABCD1234"

# 5) unbind 保留 device_id、仅清 token
client.request = lambda *a, **k: {"code":200,"message":"ok","data":None}
client.session.token_value="tok"; client.session.device_id="PYQT-SERVER-AUTHORITATIVE-ABCD1234"
client.unbind_device()
assert client.session.device_id == "PYQT-SERVER-AUTHORITATIVE-ABCD1234"
assert client.session.token_value == ""

# 6) 环境变量覆盖
os.environ["PDK_DEVICE_ID"]="PYQT-INSTANCE-B"
assert C.default_device_id(1)=="PYQT-INSTANCE-B"
print("ALL_DEVICE_ID_OK")
PY
```

### 6.2 后端行为验证
- 注册 / 登录响应里检查 `data.deviceId` 是否回传；
- 调用 `unbind-device` 后查 `pdk_user.device_id` 应**非空**；
- 用另一个 device_id 登录同账号 → 旧会话后续请求应 40103。

### 6.3 全量回归
```bash
cd E:/pdk/client-pyqt && python run_tests.py
# 预期：8 PASS / 0 FAIL / 16 SKIP（SKIP 多为需登录态 / 真实卡密 / fixed-code）
```

---

## 7. 客户端对接

### 7.1 请求头约定
所有客户端请求携带：
```
satoken: <登录后 token>
X-PDK-Phone: <手机号>
X-PDK-Device-ID: <设备标识>
```
`PdkApiClient.request()` 在 `authenticated=True` 时自动附加；device_id 取自 `self.session.device_id`。

### 7.2 对接步骤（推荐顺序）
1. **初始化设备 ID**：`device_id = default_device_id()`（自动处理缓存 / 环境变量 / 指纹）。
2. **发短信验证码**：`client.send_sms(phone, "REGISTER")`（注意 60 秒限频 42901）。
3. **注册 / 登录**：`client.register(...)` / `client.login(...)` → 服务端权威 deviceId 自动写回。
4. **调用业务接口**：如 `acquire_token`、`report_result`、`resource_status` 等（均需鉴权头）。
5. **解绑**：`client.unbind_device()` → 仅清登录态，device_id 保留。

### 7.3 PyQt 接口调试页（Swagger 风格）
GUI「接口调试」页列出全部 13 个 client API，每张卡片含：
- METHOD + Path 标题（GET 绿 / POST 蓝）
- 参数输入框（手机号 / 密码 / 设备 ID / 短信验证码自动带入全局配置）
- 实时请求预览
- 发送按钮（后台线程，不卡界面）
- 响应区（HTTP 状态 / code / message / 完整报文）
- 期待结果说明

### 7.4 完整模拟下单流程（acquire → report）
```python
client = PdkApiClient()
# 登录（自动写回 device_id）
client.login(phone, password, default_device_id())

# 1) 领资源（不扣次数，返回 leaseTraceId）
acq = client.acquire_token(action_type="GOODS_COLLECT", goods_id="881920391204")
lease_id = acq["data"]["leaseTraceId"]

# 2) 上报成功（SUCCESS 才扣费：小号 used_calls+1，用户 remaining_calls-1）
rep = client.report_result(lease_trace_id=lease_id, status="SUCCESS")

# 3) 校验
usage = client.usage()        # remainingCalls 应比下单前少 1
status = client.resource_status()  # 被领小号 usedCalls+1 且 healthStatus 回到 HEALTHY
```
> 注意：`FAIL_ACCOUNT_BANNED` / `FAIL_NETWORK` 上报**不扣费**；上报按 `leaseTraceId` **幂等**，重复上报跳过。

### 7.5 API 方法速查（`pdk_client.py`）
| 方法 | 说明 |
|---|---|
| `send_sms(phone, purpose)` | 发送短信验证码 |
| `register(phone, password, device_id, sms_code, ...)` | 注册（写回 deviceId） |
| `login(phone, password, device_id)` | 登录（写回 deviceId） |
| `logout()` | 注销 |
| `unbind_device()` | 解绑（保留 device_id） |
| `change_password(...)` | 修改密码 |
| `activate_card(...)` | 卡密核销 |
| `acquire_token(action_type, goods_id, ...)` | 加密 Token 下发 |
| `report_result(lease_trace_id, status, ...)` | 执行结果上报 |
| `profile()` / `usage()` / `resource_status()` | 账号资料 / 使用统计 / 小号使用情况 |

---

## 8. 当前状态与上线须知

- 文档对应的代码改动（后端 `ClientAuthController.java` + 客户端 `pdk_client.py` / `main.py`）**截至本文档撰写时尚未提交本地 git**；
- **后端必须重启**后 `unbind-device` 新语义才生效；
- 部署前请确认：① 数据库 `pdk_user.device_id` 列存在；② 本地缓存目录 `ProgramData\PDK\{app_id}\`（Windows 机器级、所有用户可读）或 `~/.pdk_client/{app_id}`（其他）有写权限；
- 如需「解绑即彻底换设备」的语义，请另行评估（会破坏方案 A 的「复用同一 device_id」约定）。
