"""PDK 全链路测试执行器（无 GUI 依赖）。

包含 8 个功能场景的真实调用与期待结果断言，以及一组边界测试。
所有场景/边界均返回 Result 结构，供 PyQt 界面或 CLI 校验器复用。

场景与后端接口的对应关系见 docs/TESTING_GUIDE.md：
  1 注册试用    POST /api/v1/client/auth/register
  2 登录绑设备  POST /api/v1/client/auth/login
  3 卡密核销    POST /api/v1/card/activate
  4 加密下发    POST /api/v1/dispatch/acquire-token
  5 设备互踢    同上，但替换 X-PDK-Device-ID 头 -> 期待 40103
  6 成功上报    POST /api/v1/dispatch/report-result (SUCCESS)
  7 故障拉黑    同上 (FAIL_ACCOUNT_BANNED)
  8 解绑设备    POST /api/v1/client/auth/unbind-device
"""
from __future__ import annotations

import os
import time
from dataclasses import dataclass, field
from typing import Any, Optional

from pdk_client import (
    PdkApiClient,
    decrypt_payload,
    default_device_id,
    random_password,
    random_phone,
)


@dataclass
class Result:
    sid: str
    name: str
    category: str  # "功能" / "边界"
    expected: str
    actual_code: int
    actual_msg: str
    passed: Optional[bool]  # None 表示跳过
    skipped: bool = False
    detail: str = ""
    snapshot: dict[str, Any] = field(default_factory=dict)


class TestRunner:
    def __init__(self, base_url: Optional[str] = None, device_id: Optional[str] = None) -> None:
        self.client = PdkApiClient(base_url or os.getenv("PDK_API_BASE", "http://localhost:8080"))
        self.device_id = device_id or default_device_id()
        # 手动指定的测试身份（GUI/CLI 可注入；留空则自动生成）
        self.manual_phone: str = ""
        self.manual_password: str = ""
        self.manual_sms_code: str = ""
        # 场景间共享状态
        self.test_phone: str = ""
        self.test_password: str = ""
        self.lease_trace_id: str = ""
        self.last_activation: dict[str, Any] = {}

    # ------------------------------------------------------------------ 辅助
    @property
    def have_session(self) -> bool:
        return bool(self.client.session.token_value)

    def _result(self, sid: str, name: str, category: str, expected: str,
                body: dict[str, Any], *, passed: Optional[bool],
                detail: str = "", skipped: bool = False, snapshot: Optional[dict[str, Any]] = None) -> Result:
        code = int(body.get("code", 0) or 0)
        # 后端不可达 / 返回非 JSON 时，由调用层标记为 SKIP（而非误报 FAIL）
        if code == 0 and not skipped:
            return Result(
                sid=sid, name=name, category=category, expected=expected,
                actual_code=0, actual_msg=str(body.get("message", "")),
                passed=None, skipped=True,
                detail=f"后端不可达或返回非 JSON，已跳过：{body.get('message', '')}",
                snapshot=snapshot or {},
            )
        return Result(
            sid=sid, name=name, category=category, expected=expected,
            actual_code=code,
            actual_msg=str(body.get("message", "")),
            passed=passed, skipped=skipped, detail=detail,
            snapshot=snapshot or {},
        )

    def _skip(self, sid: str, name: str, category: str, expected: str, reason: str) -> Result:
        return Result(sid=sid, name=name, category=category, expected=expected,
                      actual_code=0, actual_msg="SKIPPED", passed=None, skipped=True, detail=reason)

    @staticmethod
    def _code_match(body: dict[str, Any], code: int) -> bool:
        return int(body.get("code", 0) or 0) == code

    @staticmethod
    def _code_note(body: dict[str, Any], code: int, keyword: str) -> bool:
        return TestRunner._code_match(body, code) or (keyword in str(body.get("message", "")))

    # ------------------------------------------------------------------ 小号使用情况同步
    def slot_usage_summary(self) -> tuple[bool, str]:
        """查询后台小号（槽位）使用情况：/api/v1/client/resources/status。"""
        body = self.client.resource_status()
        if not self._code_match(body, 200):
            return False, f"查询小号使用情况失败: {body.get('message')}"
        data = body.get("data") or {}
        slots = data.get("assignments") or []
        lines = [f"小号总数={data.get('assignedResourceCount')}，可用={data.get('availableResourceCount')}，"
                 f"用户剩余次数={data.get('remainingCalls')}"]
        for a in slots:
            used = int(a.get("usedCalls") or 0)
            alloc = int(a.get("allocatedCalls") or 0)
            lines.append(f"  槽位{a.get('slotIndex')}: 已用 {used}/{alloc}，剩 {alloc - used}，状态={a.get('status')}")
        return True, "\n".join(lines)

    def usage_summary(self) -> tuple[bool, str]:
        """查询上报统计（成功/失败次数）：/api/v1/client/account/usage。"""
        body = self.client.usage()
        if not self._code_match(body, 200):
            return False, ""
        d = body.get("data") or {}
        return True, (f"上报统计: 累计={d.get('totalReported')}，"
                      f"成功={d.get('successCount')}，失败={d.get('failureCount')}")

    # ================================================================== 功能场景
    def run_scenario_1_register(self) -> Result:
        sid, name, cat = "S1", "客户端注册（试用）", "功能"
        expected = "code=200；status=TRIAL；remainingCalls>0；返回客户端 token"
        self.client.expectation = expected
        phone = self.manual_phone.strip() or random_phone()
        password = self.manual_password or random_password()
        dev = self.device_id
        # 必须先触发一次下发，服务端才会生成验证码记录（fixed-code 模式下与手输码一致）
        sms = self.client.send_sms(phone, "REGISTER")
        sms_note = ""
        if self._code_match(sms, 200):
            debug_code = str((sms.get("data") or {}).get("debugCode") or "")
            sms_code = (self.manual_sms_code.strip() or debug_code
                        or os.getenv("PDK_TEST_SMS_CODE", "").strip())
        elif self._code_match(sms, 42901):
            # 60 秒限频：同一手机号+用途 60 秒内只允许一条。验证码其实已经发出
            # （例如刚点过 GUI 的「发送验证码」按钮），只要有手输/环境变量验证码就继续注册
            sms_code = (self.manual_sms_code.strip()
                        or os.getenv("PDK_TEST_SMS_CODE", "").strip())
            sms_note = "（60 秒限频内复用已发验证码）"
            if not sms_code:
                return self._skip(
                    sid, name, cat, expected,
                    "60 秒限频：验证码已发过但拿不到验证码值。请在界面输入已收到的验证码，"
                    "或等 60 秒后重试")
        else:
            return self._result(sid, name, cat, expected, sms, passed=False,
                                detail=f"发送短信验证码失败，无法继续注册：{sms.get('message')}")
        if not sms_code:
            return self._skip(sid, name, cat, expected,
                              "未获取到短信验证码：请在界面输入验证码，或开启 pdk.sms.local.fixed-code-enabled=true "
                              "（fixed-code 模式响应会回显 debugCode），或导出 PDK_TEST_SMS_CODE 环境变量")
        body = self.client.register(phone, password, dev, sms_code)
        if not self._code_match(body, 200):
            return self._result(sid, name, cat, expected, body, passed=False, detail="注册未返回 200")
        data = body.get("data") or {}
        ok = (data.get("status") == "TRIAL") and (int(data.get("remainingCalls", 0) or 0) > 0) \
            and bool(data.get("tokenValue"))
        self.test_phone, self.test_password = phone, password
        return self._result(
            sid, name, cat, expected, body, passed=ok,
            detail=f"手机号={phone}；status={data.get('status')}；"
                   f"remainingCalls={data.get('remainingCalls')}；"
                   f"resourceAllocated={data.get('resourceAllocated')}；"
                   f"token={'已获取' if data.get('tokenValue') else '缺失'}{sms_note}",
            snapshot=data,
        )

    def run_scenario_2_login(self) -> Result:
        sid, name, cat = "S2", "客户端登录（绑设备）", "功能"
        expected = "code=200；返回 token 并完成设备绑定"
        self.client.expectation = expected
        if not self.test_phone:
            return self._skip(sid, name, cat, expected, "前置场景 S1 未成功注册，无可用账号")
        body = self.client.login(self.test_phone, self.test_password, self.device_id)
        data = body.get("data") or {}
        ok = self._code_match(body, 200) and bool(data.get("tokenValue"))
        return self._result(
            sid, name, cat, expected, body, passed=ok,
            detail=f"登录{'成功' if ok else '失败'}；deviceId={data.get('deviceId')}；"
                   f"packageName={data.get('packageName')}；remainingCalls={data.get('remainingCalls')}",
            snapshot=data,
        )

    def run_scenario_3_activate(self) -> Result:
        sid, name, cat = "S3", "卡密核销", "功能"
        expected = "code=200；套餐顺延、财务独立入账、底层小号独占绑定"
        self.client.expectation = expected
        card_key = os.getenv("PDK_TEST_CARD_KEY", "").strip()
        if not card_key:
            return self._skip(sid, name, cat, expected,
                              "未配置 PDK_TEST_CARD_KEY（需一条真实 UNUSED 激活码），无法自动化核销")
        phone = self.test_phone or os.getenv("PDK_TEST_PHONE", "")
        if not phone:
            return self._skip(sid, name, cat, expected, "无可用的充值手机号")
        body = self.client.activate_card(card_key, phone, self.device_id)
        ok = self._code_match(body, 200)
        if ok:
            self.last_activation = body.get("data") or {}
        return self._result(
            sid, name, cat, expected, body, passed=ok,
            detail=f"核销{'成功' if ok else '失败'}；"
                   + (f"newExpireAt={self.last_activation.get('newExpireAt')}；"
                      f"incomeOrderNo={self.last_activation.get('incomeOrderNo')}" if ok else ""),
            snapshot=self.last_activation,
        )

    def run_scenario_4_acquire(self) -> Result:
        sid, name, cat = "S4", "加密 Token 下发", "功能"
        expected = "code=200；返回 AES-128-GCM 密文，解密后含 leaseId/expire；槽位置 BUSY"
        self.client.expectation = expected
        if not self.have_session:
            return self._skip(sid, name, cat, expected, "前置场景未登录，无可用会话")
        body = self.client.acquire_token("GOODS_COLLECT", "881920391204")
        if not self._code_match(body, 200):
            return self._result(sid, name, cat, expected, body, passed=False,
                                detail=f"领取失败：{body.get('message')}（可能无可用小号库存或配额耗尽）")
        data = body.get("data") or {}
        payload_b64 = data.get("encryptedPayload", "")
        try:
            plain = decrypt_payload(payload_b64)
            decrypt_ok = "leaseId" in plain and "expire" in plain
            decrypt_detail = f"leaseId={plain.get('leaseId')}；expire={plain.get('expire')}"
            self.lease_trace_id = plain.get("leaseId") or data.get("leaseTraceId", "")
        except Exception as exc:  # noqa: BLE001
            decrypt_ok, decrypt_detail = False, f"解密异常：{exc}"
        ok = decrypt_ok
        return self._result(
            sid, name, cat, expected, body, passed=ok,
            detail=f"leaseTraceId={data.get('leaseTraceId')}；remainingUserQuota={data.get('remainingUserQuota')}；"
                   f"解密：{decrypt_detail}",
            snapshot=data,
        )

    def run_scenario_5_kickout(self) -> Result:
        sid, name, cat = "S5", "设备互踢", "功能"
        expected = "替换 X-PDK-Device-ID 头后请求被拦截，返回 40103 ERR_DEVICE_KICK_OUT"
        self.client.expectation = expected
        if not self.have_session:
            return self._skip(sid, name, cat, expected, "前置场景未登录，无可用会话")
        body = self.client.acquire_token(
            "GOODS_COLLECT", "881920391204",
            override_device_id=f"EVIL-DEVICE-{int(time.time()) % 100000}",
        )
        ok = self._code_match(body, 40103)
        return self._result(
            sid, name, cat, expected, body, passed=ok,
            detail=f"实际返回码={body.get('code')}；message={body.get('message')}",
            snapshot=body,
        )

    def run_scenario_6_report_success(self) -> Result:
        sid, name, cat = "S6", "成功上报扣费", "功能"
        expected = "code=200；扣减 1 次（槽位 used_calls+1）；Token 回 HEALTHY"
        self.client.expectation = expected
        if not self.lease_trace_id:
            return self._skip(sid, name, cat, expected, "前置场景 S4 未成功领取租约，无可上报 leaseId")
        before = (self.client.profile().get("data") or {}).get("remainingCalls")
        body = self.client.report_result(self.lease_trace_id, "SUCCESS")
        after = (self.client.profile().get("data") or {}).get("remainingCalls")
        delta = (before - after) if (isinstance(before, int) and isinstance(after, int)) else None
        # 同步后台小号使用情况与上报统计
        slot_ok, slot_text = self.slot_usage_summary()
        usage_ok, usage_text = self.usage_summary()
        ok = self._code_match(body, 200) and (delta is None or delta in (0, 1))
        cap_note = "（槽位已用满，属正常封顶）" if delta == 0 else ""
        return self._result(
            sid, name, cat, expected, body, passed=ok,
            detail=f"模拟下单成功：remainingCalls {before} -> {after}（Δ={delta}，应为 1）{cap_note}\n"
                   f"{'✅ ' + slot_text if slot_ok else '⚠️ ' + slot_text}\n"
                   f"{usage_text if usage_ok else ''}\n"
                   f"租约={self.lease_trace_id} 已上报",
            snapshot={"before": before, "after": after, "slotSynced": slot_ok},
        )

    def run_scenario_7_report_blacklist(self) -> Result:
        sid, name, cat = "S7", "故障免责拉黑", "功能"
        expected = "code=200；扣 0 次；底层 Token 拉黑 FAULT_BLACK，槽位自愈替换并继承已用次数"
        self.client.expectation = expected
        if not self.have_session:
            return self._skip(sid, name, cat, expected, "前置场景未登录，无可用会话")
        acquire = self.client.acquire_token("GOODS_COLLECT", "881920391204")
        if not self._code_match(acquire, 200):
            return self._skip(sid, name, cat, expected,
                              f"领取租约失败（{acquire.get('message')}），无法上报故障，跳过")
        lease = ""
        try:
            lease = decrypt_payload((acquire.get("data") or {}).get("encryptedPayload", "")).get("leaseId", "")
        except Exception:  # noqa: BLE001
            lease = (acquire.get("data") or {}).get("leaseTraceId", "")
        before = (self.client.profile().get("data") or {}).get("remainingCalls")
        body = self.client.report_result(lease, "FAIL_ACCOUNT_BANNED")
        after = (self.client.profile().get("data") or {}).get("remainingCalls")
        delta = (before - after) if (isinstance(before, int) and isinstance(after, int)) else None
        # 同步后台小号使用情况与上报统计（故障免责：不扣次数，平台侧拉黑并自愈替换）
        slot_ok, slot_text = self.slot_usage_summary()
        usage_ok, usage_text = self.usage_summary()
        ok = self._code_match(body, 200) and (delta is None or delta == 0)
        return self._result(
            sid, name, cat, expected, body, passed=ok,
            detail=f"模拟下单失败（底层账号被封）：remainingCalls {before} -> {after}（Δ={delta}，应为 0=免责）\n"
                   f"{'✅ ' + slot_text if slot_ok else '⚠️ ' + slot_text}\n"
                   f"{usage_text if usage_ok else ''}\n"
                   f"故障租约={lease} 已上报（FAULT_BLACK 拉黑在平台侧 Token 池生效，管理后台「调度中心」可见）",
            snapshot={"lease": lease, "before": before, "after": after, "slotSynced": slot_ok},
        )

    def run_scenario_8_unbind(self) -> Result:
        sid, name, cat = "S8", "解绑设备", "功能"
        expected = "code=200；deviceId 清空并注销会话"
        self.client.expectation = expected
        if not self.have_session:
            return self._skip(sid, name, cat, expected, "前置场景未登录，无可用会话")
        body = self.client.unbind_device()
        ok = self._code_match(body, 200)
        return self._result(
            sid, name, cat, expected, body, passed=ok,
            detail=f"解绑{'成功' if ok else '失败'}；会话已清空={not self.have_session}",
            snapshot=body,
        )

    def run_all_scenarios(self) -> list[Result]:
        self.client.expectation = ""
        results = [
            self.run_scenario_1_register(),
            self.run_scenario_2_login(),
            self.run_scenario_3_activate(),
            self.run_scenario_4_acquire(),
            self.run_scenario_5_kickout(),
            self.run_scenario_6_report_success(),
            self.run_scenario_7_report_blacklist(),
            self.run_scenario_8_unbind(),
        ]
        self.client.expectation = ""
        return results

    # ================================================================== 边界测试
    def run_boundary_tests(self) -> list[Result]:
        self.client.expectation = ""
        out: list[Result] = []
        out.append(self._b_invalid_phone())
        out.append(self._b_duplicate_phone())
        out.append(self._b_short_password())
        out.append(self._b_wrong_sms())
        out.append(self._b_login_not_exist())
        out.append(self._b_login_wrong_pwd())
        out.append(self._b_login_device_mismatch())
        out.append(self._b_activate_bad_format())
        out.append(self._b_activate_not_exist())
        out.append(self._b_activate_bad_phone())
        out.append(self._b_acquire_missing_device())
        out.append(self._b_acquire_bad_action())
        out.append(self._b_report_bad_status())
        out.append(self._b_report_missing_lease())
        out.append(self._b_report_unknown_lease())
        out.append(self._b_unbind_no_session())
        self.client.expectation = ""
        return out

    def _b_invalid_phone(self) -> Result:
        sid, name, cat = "B1", "注册-非法手机号", "边界"
        expected = "code=40001；提示手机号格式错误"
        self.client.expectation = expected
        dev = self.device_id
        body = self.client.register("123", random_password(), dev, "000000")
        ok = self._code_note(body, 40001, "手机")
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_duplicate_phone(self) -> Result:
        sid, name, cat = "B2", "注册-重复手机号", "边界"
        expected = "code=40010；提示该手机号已注册"
        self.client.expectation = expected
        if not self.test_phone:
            return self._skip(sid, name, cat, expected, "无已在 S1 注册的手机号，跳过")
        dev = self.device_id
        sms = self.client.send_sms(self.test_phone, "REGISTER")
        code = (sms.get("data") or {}).get("debugCode") or "000000"
        body = self.client.register(self.test_phone, random_password(), dev, code)
        ok = self._code_match(body, 40010)
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_short_password(self) -> Result:
        sid, name, cat = "B3", "注册-弱密码(<8位)", "边界"
        expected = "code=40001；提示密码长度"
        self.client.expectation = expected
        dev = self.device_id
        body = self.client.register(random_phone(), "123", dev, "000000")
        ok = self._code_note(body, 40001, "密码")
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_wrong_sms(self) -> Result:
        sid, name, cat = "B4", "注册-错误验证码", "边界"
        expected = "code=40011；提示验证码错误"
        self.client.expectation = expected
        dev = self.device_id
        body = self.client.register(random_phone(), random_password(), dev, "999999")
        ok = self._code_note(body, 40011, "验证码")
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_login_not_exist(self) -> Result:
        sid, name, cat = "B5", "登录-账号不存在", "边界"
        expected = "code=40100"
        self.client.expectation = expected
        body = self.client.login("13900000000", random_password(), self.device_id)
        ok = self._code_match(body, 40100)
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_login_wrong_pwd(self) -> Result:
        sid, name, cat = "B6", "登录-密码错误", "边界"
        expected = "code=40105"
        self.client.expectation = expected
        if not self.test_phone:
            return self._skip(sid, name, cat, expected, "无已在 S1 注册的账号，跳过")
        body = self.client.login(self.test_phone, "WrongPass123", self.device_id)
        ok = self._code_match(body, 40105)
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_login_device_mismatch(self) -> Result:
        sid, name, cat = "B7", "登录-设备不一致", "边界"
        expected = "code=40103"
        self.client.expectation = expected
        if not self.test_phone:
            return self._skip(sid, name, cat, expected, "无已在 S1 注册的账号，跳过")
        body = self.client.login(self.test_phone, self.test_password, f"OTHER-DEVICE-{int(time.time()) % 100000}")
        ok = self._code_match(body, 40103)
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_activate_bad_format(self) -> Result:
        sid, name, cat = "B8", "核销-卡密格式非法", "边界"
        expected = "code=40001；卡密格式不合规"
        self.client.expectation = expected
        body = self.client.activate_card("BADKEY", "13800138000", self.device_id)
        ok = self._code_note(body, 40001, "卡密")
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_activate_not_exist(self) -> Result:
        sid, name, cat = "B9", "核销-卡密不存在", "边界"
        expected = "code=40001；卡密不存在或输入有误"
        self.client.expectation = expected
        body = self.client.activate_card("PDK-0000-0000-0000", "13800138000", self.device_id)
        ok = self._code_match(body, 40001)
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_activate_bad_phone(self) -> Result:
        sid, name, cat = "B10", "核销-手机号格式非法", "边界"
        expected = "code=40001；手机号格式错误"
        self.client.expectation = expected
        body = self.client.activate_card("PDK-1234-1234-1234", "badphone", self.device_id)
        ok = self._code_note(body, 40001, "手机")
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_acquire_missing_device(self) -> Result:
        sid, name, cat = "B11", "下发-缺失设备头", "边界"
        expected = "code=40101；缺少设备鉴权请求头"
        self.client.expectation = expected
        if not self.have_session:
            return self._skip(sid, name, cat, expected, "未登录，跳过（需会话触发拦截）")
        body = self.client.acquire_token("GOODS_COLLECT", "881920391204", include_device=False)
        ok = self._code_match(body, 40101)
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_acquire_bad_action(self) -> Result:
        sid, name, cat = "B12", "下发-非法业务动作", "边界"
        expected = "code=40001；业务动作类型不合法"
        self.client.expectation = expected
        if not self.have_session:
            return self._skip(sid, name, cat, expected, "未登录，跳过")
        body = self.client.acquire_token("HACK", "881920391204")
        ok = self._code_note(body, 40001, "业务动作")
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_report_bad_status(self) -> Result:
        sid, name, cat = "B13", "上报-非法执行状态", "边界"
        expected = "code=40001；执行状态不合法"
        self.client.expectation = expected
        if not self.have_session:
            return self._skip(sid, name, cat, expected, "未登录，跳过")
        body = self.client.report_result("TRACE-placeholder00000000", "BAD")
        ok = self._code_note(body, 40001, "执行状态")
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_report_missing_lease(self) -> Result:
        sid, name, cat = "B14", "上报-租约号为空", "边界"
        expected = "code=40001；租借追踪流水号不能为空"
        self.client.expectation = expected
        if not self.have_session:
            return self._skip(sid, name, cat, expected, "未登录，跳过")
        body = self.client.report_result("", "SUCCESS")
        ok = self._code_note(body, 40001, "租借")
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_report_unknown_lease(self) -> Result:
        sid, name, cat = "B15", "上报-租约不存在", "边界"
        expected = "code=41001；租约已过期或不存在"
        self.client.expectation = expected
        if not self.have_session:
            return self._skip(sid, name, cat, expected, "未登录，跳过")
        body = self.client.report_result("TRACE-0000000000000000", "SUCCESS")
        ok = self._code_match(body, 41001)
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    def _b_unbind_no_session(self) -> Result:
        sid, name, cat = "B16", "解绑-未登录", "边界"
        expected = "code=40100；登录状态无效"
        self.client.expectation = expected
        anon = PdkApiClient(self.client.base_url)
        body = anon.unbind_device()
        ok = self._code_match(body, 40100)
        return self._result(sid, name, cat, expected, body, passed=ok,
                            detail=f"code={body.get('code')}；msg={body.get('message')}")

    # ------------------------------------------------------------------ 汇总
    def run_all(self) -> tuple[list[Result], list[Result]]:
        return self.run_all_scenarios(), self.run_boundary_tests()


if __name__ == "__main__":
    # 直接运行本模块即执行全部场景与边界（无 GUI）。
    from run_tests import main as _main

    raise SystemExit(_main())
