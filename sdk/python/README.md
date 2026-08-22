# PDK 客户端 SDK —— Python

Python 接入包，逻辑与 `client-pyqt/pdk_client.py` 一致，但抽成独立可分发模块，
并统一了「状态 / 事件 / 调试日志」三类回调，与 C++ / 易语言 SDK 模型完全一致。

## 安装

```bash
cd sdk/python
pip install -e .          # 或：pip install requests cryptography
```

## 快速开始

```python
from pdk import PdkApiClient, State, Event

client = PdkApiClient(base_url="http://localhost:8080")

# 三类回调（实时告诉“现在是什么状态”）
client.on_state  = lambda s, d: print(f"[状态] {s.name} —— {d}")
client.on_event  = lambda e, m: print(f"[事件] {e.name} —— {m}")
client.on_log    = lambda line: print(f"[调试] {line}")

# 注册 / 登录
r = client.register("13800138000", "Pdk12345678", "123456")
if not client.is_ok(r):
    r = client.login("13800138000", "Pdk12345678")

# 申请并解密短效 Token（核心调度）
body, plain = client.acquire_token_decrypted("GOODS_COLLECT", "881920391204")
if client.is_ok(body) and plain:
    trace_id = body["data"]["leaseTraceId"]
    # 用 plain 里的拼多多 Session 向官方发包 ...
    client.report_result(trace_id, "SUCCESS", 1200, "")   # SUCCESS 扣 1 次
```

完整示例见 `examples/demo.py`。

## API 一览

| 方法 | 说明 |
| :-- | :-- |
| `send_sms(phone, purpose)` | 发验证码 |
| `register(phone, pwd, sms, invite?)` | 注册并登录 |
| `login(phone, pwd)` | 登录（含设备校验） |
| `logout()` / `unbind_device()` / `change_password(...)` | 会话管理 |
| `activate_card(key, phone, channel?, amount?)` | 卡密核销 |
| `acquire_token(action, goods_id)` | 申请加密 Token |
| `acquire_token_decrypted(action, goods_id)` | 申请 + 解密，返回 `(body, plain_json)` |
| `decrypt_token(encrypted_payload)` | 单独解密 |
| `report_result(trace_id, status, ms?, err?)` | 上报结果 |
| `profile()` / `usage()` / `resource_status()` / `card_list()` | 查询 |

## 状态 / 事件枚举

`State`（0–15）与 `Event`（100–107）的整数值与 C++ / 易语言 完全对齐，详见 `sdk/README.md`。
`client.last_state` / `client.last_state_detail` 可用于无回调时轮询。

## 依赖

`requests`（HTTPS）、`cryptography`（AES-128-GCM）。无需手动实现加密。
