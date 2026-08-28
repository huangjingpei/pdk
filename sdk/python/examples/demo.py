"""PDK Python SDK 演示：注册 -> 申请并解密 Token -> 上报 -> 查配额，并显示状态回调。"""
from pdk import PdkApiClient, State, Event

BASE_URL = "http://localhost:8080"
PHONE = "13800138000"
PASSWORD = "Pdk12345678"
SMS_CODE = "123456"


def main() -> None:
    client = PdkApiClient(base_url=BASE_URL, app_id=1)  # PDD 客户端固定 appId=1

    # 状态回调：界面据此刷新“未登录 / 登录中 / 已登录 / 被踢”
    client.on_state = lambda s, d: print(f"[状态] {s.name} —— {d}")
    # 事件回调：解密成功 / 配额耗尽等
    client.on_event = lambda e, m: print(f"[事件] {e.name} —— {m}")
    # 调试日志：每条 HTTP 的 ▶请求/◀响应/🎯期待
    client.on_log = lambda line: print(f"[调试] {line}")

    print("== 设备ID:", client.session.device_id, "==")

    r = client.business_info()
    if not client.is_ok(r):
        print("当前业务不可用:", r.get("message"))
        return

    r = client.send_sms(PHONE, "REGISTER")
    if not client.is_ok(r):
        print("发送短信失败:", r.get("message"))
        return

    r = client.register(PHONE, PASSWORD, SMS_CODE)
    if not client.is_ok(r):
        if r.get("code") == 40010:  # 已领过体验 -> 登录
            print("（已领过体验，改用 login）")
            r = client.login(PHONE, PASSWORD)
        if not client.is_ok(r):
            print("注册/登录失败:", r.get("message"))
            return

    r = client.profile()
    if client.is_ok(r):
        data = r.get("data") or {}
        print("剩余次数:", data.get("remainingCalls"), "套餐:", data.get("packageName"))

    body, plain = client.acquire_token_decrypted("GOODS_COLLECT", "881920391204")
    if client.is_ok(body) and plain:
        print("解密后的明文 Token 报文:", plain)
        trace_id = (body.get("data") or {}).get("leaseTraceId")
        rr = client.report_result(trace_id, "SUCCESS", 1200, "")
        print("上报结果:", rr.get("code"), rr.get("message"))
    else:
        print("申请 Token 失败:", body.get("code"), body.get("message"))

    client.logout()
    print("结束。")


if __name__ == "__main__":
    main()
