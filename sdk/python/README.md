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

client = PdkApiClient(base_url="http://localhost:8080", app_id=1)  # PDD 固定为 1

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

SDK 保持所有 URL 不变，并自动为每次请求添加 `X-PDK-App-ID`。短信、注册、登录、改密和
卡密激活的 JSON 请求体也会携带同一个 `appId`。旧代码不传 `app_id` 时默认使用 1；也可通过
环境变量 `PDK_APP_ID` 设置默认值。运行期间修改 `client.app_id` 会清除旧业务登录态，避免跨业务复用 Token。

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
| `create_live_publish_ticket(title?, request_id?, protocol?)` | appId=3 登录后申请短效推流地址 |
| `live_streams()` / `stop_live_stream(session_no)` | 查询或停止自己的直播会话 |

`ZHIBO_LIVE` 客户端应以 `app_id=3` 初始化。SDK 的调试回调不会输出 `publishUrl`，因为其中含有
一次性推流票据；请将它直接交给 FFmpeg/OBS，不要写入日志、数据库或错误上报。

## 状态 / 事件枚举

`State`（0–15）与 `Event`（100–107）的整数值与 C++ / 易语言 完全对齐，详见 `sdk/README.md`。
`client.last_state` / `client.last_state_detail` 可用于无回调时轮询。

## 依赖

`requests`（HTTPS）、`cryptography`（AES-128-GCM）。无需手动实现加密。
