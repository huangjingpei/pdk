"""PDK 接口调试工作台（PyQt6 GUI）—— Swagger UI 风格。

把 docs/TESTING_GUIDE.md 里的 client API 以独立卡片呈现，每张卡片包含：
  - 请求行：METHOD + Path + 接口名称
  - 参数表单：根据接口定义动态生成输入框，默认值从顶部全局配置自动带入
  - 请求预览：实时显示将要发送的 HTTP 请求（URL / Headers / Body）
  - 响应区：HTTP 状态码 + 业务 code / message + 响应体
  - 期待结果：接口/场景的预期说明

同时保留：
  - 8 个功能场景一键回归（「场景测试」页）
  - 16 个边界测试（「边界测试」页）
  - 全局响应日志（「响应日志」页）：每条 HTTP 调用渲染为「▶ 请求 / ◀ 响应 / 🎯 期待」

底层逻辑复用 pdk_client.PdkApiClient 与 pdk_testrunner.TestRunner。
"""
from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

from PyQt6.QtCore import Qt, QThread, QTimer, pyqtSignal
from PyQt6.QtGui import QColor, QFont
from PyQt6.QtWidgets import (
    QApplication,
    QFormLayout,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QProgressDialog,
    QPushButton,
    QComboBox,
    QScrollArea,
    QTabWidget,
    QTableWidget,
    QTableWidgetItem,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from pdk_client import PdkApiClient, default_device_id, random_password, random_phone, redact_sensitive
from pdk_testrunner import Result, TestRunner
from update_client import ClientUpdateManager, UpdateError


@dataclass
class FieldDef:
    """接口参数字段定义。"""

    name: str
    label: str
    default: str = ""
    placeholder: str = ""
    password: bool = False
    required: bool = False
    width: int = 240


@dataclass
class EndpointDef:
    """接口端点定义（Swagger UI 风格卡片）。"""

    eid: str
    name: str
    method: str
    path: str
    fields: list[FieldDef] = field(default_factory=list)
    expects: str = ""
    requires_auth: bool = False
    scenario_id: Optional[str] = None
    scenario_name: Optional[str] = None


# --------------------------------------------------------------------------- 端点注册表
ENDPOINTS: list[EndpointDef] = [
    EndpointDef(
        eid="sms_send",
        name="发送短信验证码",
        method="POST",
        path="/api/v1/client/auth/sms/send",
        fields=[
            FieldDef("phone", "手机号", placeholder="从顶部配置带入", required=True),
            FieldDef("purpose", "用途", default="REGISTER", placeholder="REGISTER / RESET_PASSWORD", required=True),
        ],
        expects="code=200 下发验证码；或 42901（60 秒限频，已发的验证码 5 分钟内仍有效）",
    ),
    EndpointDef(
        eid="register",
        name="客户端注册（试用）",
        method="POST",
        path="/api/v1/client/auth/register",
        fields=[
            FieldDef("phone", "手机号", placeholder="留空则自动生成随机手机号", required=False),
            FieldDef("password", "密码", placeholder="留空则自动生成随机密码", required=False, password=True),
            FieldDef("deviceId", "设备ID", placeholder="从顶部配置带入", required=True),
            FieldDef("smsCode", "短信验证码", placeholder="fixed-code 模式自动回填", required=True),
            FieldDef("invitationCode", "邀请码", placeholder="可选", required=False),
        ],
        expects="code=200；status=TRIAL；remainingCalls>0；返回客户端 token",
        scenario_id="S1",
        scenario_name="客户端注册（试用）",
    ),
    EndpointDef(
        eid="login",
        name="客户端登录（绑设备）",
        method="POST",
        path="/api/v1/client/auth/login",
        fields=[
            FieldDef("phone", "手机号", placeholder="从顶部配置带入", required=True),
            FieldDef("password", "密码", placeholder="从顶部配置带入", required=True, password=True),
            FieldDef("deviceId", "设备ID", placeholder="从顶部配置带入", required=True),
            FieldDef("cardKey", "设备卡密（新设备）", placeholder="服务端返回40380后填写", required=False, password=True),
        ],
        expects="已绑定设备 code=200；新设备无卡 code=40380；有效卡首次绑定 code=200",
        scenario_id="S2",
        scenario_name="客户端登录（绑设备）",
    ),
    EndpointDef(
        eid="logout",
        name="注销会话",
        method="POST",
        path="/api/v1/client/auth/logout",
        expects="code=200 注销当前会话",
        requires_auth=True,
    ),
    EndpointDef(
        eid="unbind_device",
        name="解绑设备",
        method="POST",
        path="/api/v1/client/auth/unbind-device",
        expects="code=200；服务端 deviceId 清空并注销会话，可在新电脑重新绑定",
        requires_auth=True,
        scenario_id="S8",
        scenario_name="解绑设备",
    ),
    EndpointDef(
        eid="change_password",
        name="修改密码",
        method="POST",
        path="/api/v1/client/auth/change-password",
        fields=[
            FieldDef("phone", "手机号", placeholder="从顶部配置带入", required=True),
            FieldDef("oldPassword", "旧密码", placeholder="从顶部配置带入", required=True, password=True),
            FieldDef("newPassword", "新密码", placeholder="请输入新密码", required=True, password=True),
        ],
        expects="code=200 修改密码成功",
    ),
    EndpointDef(
        eid="activate_card",
        name="卡密核销",
        method="POST",
        path="/api/v1/card/activate",
        fields=[
            FieldDef("cardKey", "激活码", placeholder="PDK-XXXX-XXXX-XXXX", required=True),
            FieldDef("userPhone", "充值手机号", placeholder="从顶部配置带入", required=True),
            FieldDef("deviceId", "设备ID", placeholder="从顶部配置带入", required=True),
        ],
        expects="code=200；套餐顺延、财务独立入账、底层小号独占绑定",
        scenario_id="S3",
        scenario_name="卡密核销",
    ),
    EndpointDef(
        eid="acquire_token",
        name="加密 Token 下发",
        method="POST",
        path="/api/v1/dispatch/acquire-token",
        fields=[
            FieldDef("actionType", "业务动作", default="GOODS_COLLECT", placeholder="GOODS_COLLECT", required=True),
            FieldDef("goodsId", "商品ID", default="881920391204", placeholder="商品编号", required=True),
        ],
        expects="code=200；返回 AES-128-GCM 密文，解密后含 leaseId/expire",
        requires_auth=True,
        scenario_id="S4",
        scenario_name="加密 Token 下发",
    ),
    EndpointDef(
        eid="report_result",
        name="执行结果上报",
        method="POST",
        path="/api/v1/dispatch/report-result",
        fields=[
            FieldDef("leaseTraceId", "租约流水号", placeholder="从上一次 acquire-token 解密获得", required=True),
            FieldDef("status", "执行状态", default="SUCCESS", placeholder="SUCCESS / FAIL_ACCOUNT_BANNED", required=True),
            FieldDef("responseDurationMs", "耗时(ms)", default="1000", placeholder="毫秒", required=False),
            FieldDef("errorMessage", "错误信息", default="", placeholder="失败时填写", required=False),
        ],
        expects="code=200 上报成功（SUCCESS 扣 1 次；FAIL_ACCOUNT_BANNED 扣 0 次）",
        requires_auth=True,
        scenario_id="S6/S7",
        scenario_name="成功上报 / 故障拉黑",
    ),
    EndpointDef(
        eid="profile",
        name="账号资料",
        method="GET",
        path="/api/v1/client/account/profile",
        expects="code=200 返回当前账号信息",
        requires_auth=True,
    ),
    EndpointDef(
        eid="current_device_license",
        name="当前设备许可证",
        method="GET",
        path="/api/v1/client/device-license/current",
        expects="code=200；返回当前 licenseId、脱敏卡密、独立到期时间、次数和 serverTime",
        requires_auth=True,
    ),
    EndpointDef(
        eid="device_licenses",
        name="手机号设备许可证列表",
        method="GET",
        path="/api/v1/client/devices",
        expects="code=200；返回该手机号各设备许可证，当前卡过期时仍允许查询",
        requires_auth=True,
    ),
    EndpointDef(
        eid="device_license_renewals",
        name="当前许可证续费历史",
        method="GET",
        path="/api/v1/client/device-license/renewal-history",
        expects="code=200；返回原卡历次续费的前后到期时间、增加次数和订单号",
        requires_auth=True,
    ),
    EndpointDef(
        eid="unbind_device_license",
        name="解绑当前设备许可证",
        method="POST",
        path="/api/v1/client/device-license/unbind",
        expects="code=200；停止当前许可证推流并解绑；有效期不暂停，其他设备不受影响",
        requires_auth=True,
    ),
    EndpointDef(
        eid="usage",
        name="使用统计",
        method="GET",
        path="/api/v1/client/account/usage",
        expects="code=200 返回累计/成功/失败上报次数",
        requires_auth=True,
    ),
    EndpointDef(
        eid="resource_status",
        name="小号使用情况",
        method="GET",
        path="/api/v1/client/resources/status",
        expects="code=200 返回小号槽位分配与已用次数",
        requires_auth=True,
    ),
    EndpointDef(
        eid="card_list",
        name="已核销卡密",
        method="GET",
        path="/api/v1/client/account/card",
        expects="code=200 返回已激活卡密列表",
        requires_auth=True,
    ),
    EndpointDef(
        eid="live_publish_ticket",
        name="ZHIBO_LIVE 申请推流票据",
        method="POST",
        path="/api/v1/client/zhibo-live/publish-tickets",
        fields=[
            FieldDef("clientRequestId", "请求ID", placeholder="留空自动生成 UUID", required=False),
            FieldDef("title", "直播标题", default="客户端直播", required=False),
            FieldDef("requestedProtocol", "推流协议", default="RTMP", placeholder="当前部署仅 RTMP", required=True),
        ],
        expects="仅 appId=3 已登录且套餐有效时 code=200；返回90秒短效推流地址",
        requires_auth=True,
    ),
    EndpointDef(
        eid="live_streams",
        name="ZHIBO_LIVE 推流会话",
        method="GET",
        path="/api/v1/client/zhibo-live/streams/current",
        expects="code=200 返回当前用户推流会话及状态，响应不包含明文票据",
        requires_auth=True,
    ),
    EndpointDef(
        eid="live_stop",
        name="ZHIBO_LIVE 停止推流",
        method="POST",
        path="/api/v1/client/zhibo-live/streams/{streamSessionNo}/stop",
        fields=[FieldDef("streamSessionNo", "推流会话号", required=True)],
        expects="code=200；只能停止当前登录用户自己的推流",
        requires_auth=True,
    ),
]


# --------------------------------------------------------------------------- 通用后台线程
class Worker(QThread):
    """在后台线程执行任务，避免界面卡死。"""

    finished = pyqtSignal(object)  # 任意结果

    def __init__(self, fn: Callable[[], object]) -> None:
        super().__init__()
        self.fn = fn

    def run(self) -> None:  # noqa: D401
        try:
            self.finished.emit(self.fn())
        except Exception as exc:  # noqa: BLE001
            self.finished.emit({"error": str(exc)})


# --------------------------------------------------------------------------- Swagger 风格接口卡片
class EndpointCard(QWidget):
    """单个 API 端点的调试卡片：参数输入 + 请求预览 + 发送 + 响应展示。"""

    def __init__(self, endpoint: EndpointDef, main: "MainWindow") -> None:
        super().__init__()
        self.endpoint = endpoint
        self.main = main
        self.runner = main.runner
        self.worker: Optional[Worker] = None
        self.edits: list[QLineEdit] = []
        self._build_ui()
        self._apply_defaults()
        self.update_preview()

    def _method_color(self) -> str:
        return {"GET": "#16a34a", "POST": "#2563eb", "PUT": "#d97706", "DELETE": "#dc2626"}.get(
            self.endpoint.method, "#475569"
        )

    def _build_ui(self) -> None:
        self.setObjectName("EndpointCard")
        self.setStyleSheet(
            "#EndpointCard { border: 1px solid #cbd5e1; border-radius: 6px; background: #ffffff; }"
        )
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)

        root = QVBoxLayout(self)
        root.setSpacing(10)
        root.setContentsMargins(14, 12, 14, 12)

        # 标题行：METHOD 徽章 + Path + 名称 + 按钮
        header = QHBoxLayout()
        method_lbl = QLabel(self.endpoint.method)
        method_lbl.setStyleSheet(
            f"color:#fff;background:{self._method_color()};border-radius:4px;padding:2px 8px;font-weight:600;"
        )
        method_lbl.setFont(QFont("Consolas", 10, QFont.Weight.Bold))
        path_lbl = QLabel(self.endpoint.path)
        path_lbl.setStyleSheet("color:#334155;font-family:Consolas,monospace;font-size:13px;font-weight:600;")
        name_lbl = QLabel(self.endpoint.name)
        name_lbl.setStyleSheet("color:#0f172a;font-size:13px;font-weight:600;")

        header.addWidget(method_lbl)
        header.addWidget(path_lbl)
        header.addWidget(name_lbl)
        header.addStretch()

        if self.endpoint.scenario_id:
            self.scenario_btn = QPushButton(f"作为 {self.endpoint.scenario_id} 运行")
            self.scenario_btn.setStyleSheet("color:#2563eb;")
            self.scenario_btn.clicked.connect(self.run_scenario)
            header.addWidget(self.scenario_btn)

        self.send_btn = QPushButton("发送请求")
        self.send_btn.setStyleSheet("background:#2563eb;color:#fff;font-weight:600;padding:4px 14px;")
        self.send_btn.clicked.connect(self.call_endpoint)
        header.addWidget(self.send_btn)
        root.addLayout(header)

        # 期待结果
        exp = QLabel(f"🎯 期待：{self.endpoint.expects}")
        exp.setWordWrap(True)
        exp.setStyleSheet("color:#475569;font-size:12px")
        root.addWidget(exp)

        # 参数表单
        if self.endpoint.fields:
            form_box = QGroupBox("请求参数")
            form = QFormLayout(form_box)
            form.setLabelAlignment(Qt.AlignmentFlag.AlignRight)
            for fdef in self.endpoint.fields:
                edit = QLineEdit()
                edit.setPlaceholderText(fdef.placeholder)
                if fdef.password:
                    edit.setEchoMode(QLineEdit.EchoMode.Password)
                edit.setMinimumWidth(fdef.width)
                edit.textChanged.connect(self.update_preview)
                star = " *" if fdef.required else ""
                form.addRow(QLabel(f"{fdef.label}{star}"), edit)
                self.edits.append(edit)
            root.addWidget(form_box)
        else:
            no_param = QLabel("本接口无需请求参数")
            no_param.setStyleSheet("color:#94a3b8;font-size:12px")
            root.addWidget(no_param)

        # 请求预览
        preview_box = QGroupBox("请求预览")
        pv_layout = QVBoxLayout(preview_box)
        self.req_preview = QTextEdit()
        self.req_preview.setReadOnly(True)
        self.req_preview.setMaximumHeight(110)
        self.req_preview.setStyleSheet("background:#f8fafc;font-family:Consolas,monospace;font-size:12px;")
        pv_layout.addWidget(self.req_preview)
        root.addWidget(preview_box)

        # 响应区
        resp_box = QGroupBox("响应结果")
        resp_layout = QVBoxLayout(resp_box)
        self.resp_status = QLabel("— 未发送 —")
        self.resp_status.setStyleSheet("color:#94a3b8;font-weight:600;")
        resp_layout.addWidget(self.resp_status)
        self.resp_body = QTextEdit()
        self.resp_body.setReadOnly(True)
        self.resp_body.setMaximumHeight(180)
        self.resp_body.setStyleSheet("background:#f8fafc;font-family:Consolas,monospace;font-size:12px;")
        resp_layout.addWidget(self.resp_body)
        root.addWidget(resp_box)

    def _apply_defaults(self) -> None:
        """把顶部全局配置作为各输入框默认值。"""
        for fdef, edit in zip(self.endpoint.fields, self.edits):
            edit.setText(self.main.default_value_for(fdef.name, fdef.default))

    def default_or_placeholder(self, fdef: FieldDef) -> str:
        v = self.main.default_value_for(fdef.name, fdef.default)
        return v if v else fdef.placeholder

    def collect_values(self, for_call: bool = False) -> dict[str, str]:
        """收集表单值。注册时手机号/密码留空则自动生成（仅实际调用时）。"""
        values: dict[str, str] = {}
        for fdef, edit in zip(self.endpoint.fields, self.edits):
            v = edit.text().strip()
            if not v and self.endpoint.eid == "register" and for_call:
                if fdef.name == "phone":
                    v = random_phone()
                elif fdef.name == "password":
                    v = random_password()
            values[fdef.name] = v
        return values

    def update_preview(self) -> None:
        """实时渲染将要发送的 HTTP 请求。"""
        base = self.main.base_url.text().strip().rstrip("/") or "http://localhost:8080"
        url = base + self.endpoint.path

        headers: dict[str, str] = {
            "Accept": "application/json",
            "X-PDK-App-ID": str(self.main.current_app_id()),
        }
        if self.endpoint.requires_auth:
            sess = self.runner.client.session
            if sess.token_value:
                headers["satoken"] = sess.token_value
            if sess.phone:
                headers["X-PDK-Phone"] = sess.phone
            if sess.device_id:
                headers["X-PDK-Device-ID"] = sess.device_id

        values = self.collect_values(for_call=False)
        # 注册时若留空，在预览中提示将自动生成
        if self.endpoint.eid == "register":
            if not values.get("phone"):
                values["phone"] = "<将自动生成随机手机号>"
            if not values.get("password"):
                values["password"] = "<将自动生成随机密码>"

        body: Optional[dict[str, Any]] = None
        if self.endpoint.method != "GET":
            body = {fdef.name: values.get(fdef.name, "") for fdef in self.endpoint.fields}
            # 去掉空可选字段，避免发送空字符串
            optional = {fdef.name for fdef in self.endpoint.fields if not fdef.required}
            body = {k: v for k, v in body.items() if v or k not in optional}
            if self.endpoint.eid in {"sms_send", "register", "login", "change_password", "activate_card"}:
                body["appId"] = self.main.current_app_id()

        preview = {
            "method": self.endpoint.method,
            "url": url,
            "headers": redact_sensitive(headers),
        }
        if body is not None:
            preview["body"] = redact_sensitive(body)
        self.req_preview.setPlainText(json.dumps(preview, ensure_ascii=False, indent=2, default=str))

    def call_endpoint(self) -> None:
        """根据端点定义分发调用，并在后台线程执行。"""
        self.main._sync_config()
        self.send_btn.setEnabled(False)
        self.send_btn.setText("请求中…")
        self.resp_status.setText("请求中…")
        self.resp_status.setStyleSheet("color:#2563eb;font-weight:600;")
        self.runner.client.expectation = self.endpoint.expects

        self.worker = Worker(self._do_call)
        self.worker.finished.connect(self._on_call_done)
        self.worker.start()

    def _do_call(self) -> tuple[dict[str, Any], Optional[dict[str, Any]]]:
        values = self.collect_values(for_call=True)
        client = self.runner.client
        eid = self.endpoint.eid
        client.last_request_record = None

        try:
            if eid == "sms_send":
                resp = client.send_sms(values["phone"], values.get("purpose", "REGISTER"))
            elif eid == "register":
                resp = client.register(
                    values["phone"], values["password"], values["deviceId"],
                    values["smsCode"], values.get("invitationCode", "")
                )
            elif eid == "login":
                resp = client.login(values["phone"], values["password"], values["deviceId"], values.get("cardKey", ""))
            elif eid == "logout":
                resp = client.logout()
            elif eid == "unbind_device":
                resp = client.unbind_device()
            elif eid == "change_password":
                resp = client.change_password(values["phone"], values["oldPassword"], values["newPassword"])
            elif eid == "activate_card":
                resp = client.activate_card(values["cardKey"], values["userPhone"], values["deviceId"])
            elif eid == "acquire_token":
                resp = client.acquire_token(values["actionType"], values["goodsId"])
            elif eid == "report_result":
                duration = int(values.get("responseDurationMs") or 1000)
                resp = client.report_result(
                    values["leaseTraceId"], values["status"],
                    duration_ms=duration, error_message=values.get("errorMessage", "")
                )
            elif eid == "profile":
                resp = client.profile()
            elif eid == "current_device_license":
                resp = client.current_device_license()
            elif eid == "device_licenses":
                resp = client.device_licenses()
            elif eid == "device_license_renewals":
                resp = client.device_license_renewals()
            elif eid == "unbind_device_license":
                resp = client.unbind_device_license()
            elif eid == "usage":
                resp = client.usage()
            elif eid == "resource_status":
                resp = client.resource_status()
            elif eid == "card_list":
                resp = client.card_list()
            elif eid == "live_publish_ticket":
                resp = client.create_live_publish_ticket(
                    values.get("clientRequestId", ""), values.get("title", "客户端直播"),
                    values.get("requestedProtocol", "RTMP"))
            elif eid == "live_streams":
                resp = client.live_streams()
            elif eid == "live_stop":
                resp = client.stop_live_stream(values["streamSessionNo"])
            else:
                resp = {"code": 0, "message": f"未实现端点 {eid}", "data": None}
        except Exception as exc:  # noqa: BLE001
            resp = {"code": 0, "message": f"本地异常: {exc}", "data": None}

        rec = client.last_request_record
        return resp, rec

    def _on_call_done(self, payload: object) -> None:
        self.send_btn.setEnabled(True)
        self.send_btn.setText("发送请求")
        if isinstance(payload, dict) and "error" in payload:
            self._render_response({"code": 0, "message": f"执行异常: {payload['error']}", "data": None}, None)
            return
        resp, rec = payload  # type: ignore[misc]
        self._render_response(resp, rec)
        # 更新登录状态显示
        self.main.refresh_login_state()

    def _render_response(self, resp: dict[str, Any], rec: Optional[dict[str, Any]]) -> None:
        http_status = rec.get("http_status", "—") if rec else "—"
        code = int(resp.get("code", 0) or 0)
        msg = str(resp.get("message", ""))

        if code == 200:
            color, text = "#047857", "成功"
        elif code == 0:
            color, text = "#d97706", "本地异常/未连通"
        else:
            color, text = "#dc2626", "业务异常"

        self.resp_status.setText(f"HTTP {http_status}  |  code={code}  |  {text}  |  {msg}")
        self.resp_status.setStyleSheet(f"color:{color};font-weight:600;")
        self.resp_body.setPlainText(json.dumps(redact_sensitive(resp), ensure_ascii=False, indent=2, default=str))

    def run_scenario(self) -> None:
        """调用 TestRunner 的对应场景方法（带前后依赖）。"""
        fn = self.main.scenario_fn(self.endpoint.scenario_id)
        if fn is None:
            QMessageBox.warning(self, "提示", f"未找到场景 {self.endpoint.scenario_id} 的实现")
            return
        self.main._sync_manual()
        self.scenario_btn.setEnabled(False)
        self.scenario_btn.setText("运行中…")
        self.worker = Worker(fn)
        self.worker.finished.connect(self._on_scenario_done)
        self.worker.start()

    def _on_scenario_done(self, result: Result) -> None:
        self.scenario_btn.setEnabled(True)
        self.scenario_btn.setText(f"作为 {self.endpoint.scenario_id} 运行")
        if isinstance(result, Result):
            self._render_response({"code": result.actual_code, "message": result.actual_msg, "data": result.snapshot}, None)
        else:
            self._render_response({"code": 0, "message": "场景执行异常", "data": None}, None)


# --------------------------------------------------------------------------- 场景回归卡片
class ScenarioBox(QGroupBox):
    """单个功能场景卡片：标题 + 期待结果 + 运行按钮 + 结果展示。"""

    def __init__(self, sid: str, name: str, expected: str, runner: TestRunner,
                 run_fn: Callable[[], Result], parent: Optional[QWidget] = None) -> None:
        super().__init__(f"{sid} · {name}", parent)
        self.sid = sid
        self.runner = runner
        self.run_fn = run_fn
        self.worker: Optional[Worker] = None

        layout = QVBoxLayout(self)
        layout.setSpacing(8)

        exp = QLabel(f"期待：{expected}")
        exp.setWordWrap(True)
        exp.setStyleSheet("color:#475569;font-size:12px")
        layout.addWidget(exp)

        row = QHBoxLayout()
        self.run_btn = QPushButton("运行")
        self.run_btn.clicked.connect(self.run)
        self.badge = QLabel("— 未运行 —")
        self.badge.setStyleSheet("color:#94a3b8;font-weight:600")
        row.addWidget(self.run_btn)
        row.addWidget(self.badge)
        row.addStretch()
        layout.addLayout(row)

        self.detail = QLabel("")
        self.detail.setWordWrap(True)
        self.detail.setStyleSheet("color:#334155;font-size:12px;min-height:18px")
        layout.addWidget(self.detail)

        self.raw = QTextEdit()
        self.raw.setReadOnly(True)
        self.raw.setMaximumHeight(120)
        self.raw.setPlaceholderText("原始响应报文")
        layout.addWidget(self.raw)

    def run(self) -> None:
        self.run_btn.setEnabled(False)
        self.badge.setText("运行中…")
        self.badge.setStyleSheet("color:#2563eb;font-weight:600")
        self.worker = Worker(self.run_fn)
        self.worker.finished.connect(self.on_done)
        self.worker.start()

    def on_done(self, result: Result) -> None:
        self.run_btn.setEnabled(True)
        if isinstance(result, Result):
            self.show_result(result)
        else:
            self.badge.setText("异常")
            self.badge.setStyleSheet("color:#dc2626;font-weight:600")

    def show_result(self, r: Result) -> None:
        if r.skipped or r.passed is None:
            self.badge.setText("SKIP")
            self.badge.setStyleSheet("color:#d97706;font-weight:600")
        elif r.passed:
            self.badge.setText("PASS ✅")
            self.badge.setStyleSheet("color:#047857;font-weight:600")
        else:
            self.badge.setText("FAIL ❌")
            self.badge.setStyleSheet("color:#dc2626;font-weight:600")
        self.detail.setText(f"实际：code={r.actual_code}  {r.actual_msg}\n{r.detail}")
        try:
            self.raw.setPlainText(json.dumps(r.snapshot, ensure_ascii=False, indent=2, default=str))
        except Exception:  # noqa: BLE001
            self.raw.setPlainText(str(r.snapshot))


# --------------------------------------------------------------------------- 主窗口
class MainWindow(QMainWindow):
    # 单条 HTTP 请求记录（跨线程安全回传 GUI 主线程渲染）
    http_log_ready = pyqtSignal(dict)

    def __init__(self) -> None:
        super().__init__()
        self.runner = TestRunner(device_id=default_device_id())
        self.http_log_ready.connect(self.append_http)
        # 每条 HTTP 请求回传「请求 + 响应 + 期待」，便于客户端调试
        self.runner.client.on_request = lambda rec: self.http_log_ready.emit(rec)
        self.log = QTextEdit()
        self.log.setReadOnly(True)
        # 短信 60 秒限频倒计时（与后端 SmsCodeService 的 60s 限频一致）
        self._sms_retry_after = 0
        self._sms_timer = QTimer(self)
        self._sms_timer.setInterval(1000)
        self._sms_timer.timeout.connect(self._tick_sms_countdown)
        self.endpoint_cards: list[EndpointCard] = []
        self.init_ui()

    # ------------------------------------------------------------------ UI
    def init_ui(self) -> None:
        self.setWindowTitle("PDK 全链路测试工作台")
        self.resize(1180, 860)
        root = QWidget()
        layout = QVBoxLayout(root)
        layout.setSpacing(10)

        layout.addWidget(self._connection_box())

        top_row = QHBoxLayout()
        self.run_all_scenarios_btn = QPushButton("运行全部功能场景")
        self.run_all_scenarios_btn.clicked.connect(self.run_all_scenarios)
        self.run_all_boundary_btn = QPushButton("运行全部边界测试")
        self.run_all_boundary_btn.clicked.connect(self.run_all_boundary)
        self.reload_defaults_btn = QPushButton("重载接口默认值")
        self.reload_defaults_btn.setToolTip("把顶部手机号/密码/设备ID/验证码重新同步到各接口输入框")
        self.reload_defaults_btn.clicked.connect(self.reload_endpoint_defaults)
        top_row.addWidget(self.run_all_scenarios_btn)
        top_row.addWidget(self.run_all_boundary_btn)
        top_row.addWidget(self.reload_defaults_btn)
        top_row.addStretch()
        layout.addLayout(top_row)

        tabs = QTabWidget()
        tabs.addTab(self._endpoints_tab(), "接口调试")
        tabs.addTab(self._scenarios_tab(), "场景测试（8）")
        tabs.addTab(self._boundary_tab(), "边界测试（16）")
        tabs.addTab(self._log_tab(), "响应日志")
        layout.addWidget(tabs, 1)
        self.setCentralWidget(root)

    def _connection_box(self) -> QGroupBox:
        box = QGroupBox("连接与客户端身份")
        grid = QGridLayout(box)
        self.base_url = QLineEdit(self.runner.client.base_url)
        self.app_id = QComboBox()
        self.app_id.addItem("拼多多 / PDD (1)", 1)
        self.app_id.addItem("zhibo-ai (2)", 2)
        self.app_id.addItem("zhibo-live (3)", 3)
        configured_app_id = int(self.runner.build_config["appId"])
        if self.app_id.findData(configured_app_id) < 0:
            self.app_id.addItem(
                f"{self.runner.build_config.get('displayName', '自定义业务')} ({configured_app_id})",
                configured_app_id,
            )
        initial_app_index = self.app_id.findData(self.runner.client.app_id)
        self.app_id.setCurrentIndex(max(initial_app_index, 0))
        build_config = self.runner.build_config
        if not bool(build_config.get("productionEditable", True)):
            self.app_id.setEnabled(False)
            self.app_id.setToolTip(
                f"生产构建已由 {build_config.get('configPath', '构建配置')} 固定 appId={build_config['appId']}"
            )
        self.app_id.currentIndexChanged.connect(self._on_app_id_changed)
        self.phone = QLineEdit("")
        self.password = QLineEdit("")
        self.password.setEchoMode(QLineEdit.EchoMode.Password)
        self.device_id = QLineEdit(self.runner.device_id)
        self.sms_code = QLineEdit("")
        self.sms_code.setPlaceholderText("注册用验证码；fixed-code 模式点「发送验证码」自动回填")
        self.license_card = QLineEdit("")
        self.license_card.setEchoMode(QLineEdit.EchoMode.Password)
        self.license_card.setPlaceholderText("仅许可证业务新电脑首次登录需要；不会保存或打印")
        self.login_state = QLabel("未登录")
        self.login_state.setStyleSheet("color:#dc2626;font-weight:600")
        self.business_state = QLabel("业务信息待加载")
        self.business_state.setWordWrap(True)
        self.business_state.setStyleSheet("color:#475569;font-size:12px")
        self.business_worker: Optional[Worker] = None

        login = QPushButton("登录")
        login.clicked.connect(self.do_login)
        logout = QPushButton("注销会话")
        logout.clicked.connect(self.do_logout)
        unbind = QPushButton("解绑设备")
        unbind.clicked.connect(self.do_unbind)
        self.send_sms_btn = QPushButton("发送验证码")
        self.send_sms_btn.clicked.connect(self.do_send_sms)
        slots_btn = QPushButton("查询小号使用情况")
        slots_btn.clicked.connect(self.do_resource_status)

        grid.addWidget(QLabel("服务地址"), 0, 0)
        grid.addWidget(self.base_url, 0, 1)
        grid.addWidget(QLabel("业务/AppID"), 0, 2)
        grid.addWidget(self.app_id, 0, 3)
        grid.addWidget(QLabel("手机号"), 1, 0)
        grid.addWidget(self.phone, 1, 1)
        grid.addWidget(QLabel("登录密码"), 1, 2)
        grid.addWidget(self.password, 1, 3)
        grid.addWidget(QLabel("设备ID"), 2, 0)
        grid.addWidget(self.device_id, 2, 1)
        grid.addWidget(QLabel("短信验证码"), 2, 2)
        grid.addWidget(self.sms_code, 2, 3)
        grid.addWidget(QLabel("设备许可证卡密"), 3, 0)
        grid.addWidget(self.license_card, 3, 1, 1, 3)
        grid.addWidget(self.send_sms_btn, 4, 0)
        grid.addWidget(slots_btn, 4, 1)
        btn_row = QHBoxLayout()
        btn_row.addWidget(login)
        btn_row.addWidget(logout)
        btn_row.addWidget(unbind)
        btn_row.addWidget(self.login_state)
        btn_row.addStretch()
        grid.addLayout(btn_row, 5, 0, 1, 4)
        grid.addWidget(self.business_state, 6, 0, 1, 4)
        QTimer.singleShot(0, self._refresh_business_info)
        return box

    def _endpoints_tab(self) -> QWidget:
        scroll = QScrollArea()
        inner = QWidget()
        vbox = QVBoxLayout(inner)
        vbox.setSpacing(14)
        for ep in ENDPOINTS:
            card = EndpointCard(ep, self)
            self.endpoint_cards.append(card)
            vbox.addWidget(card)
        vbox.addStretch()
        scroll.setWidget(inner)
        scroll.setWidgetResizable(True)
        return scroll

    def _scenarios_tab(self) -> QWidget:
        scroll = QScrollArea()
        inner = QWidget()
        vbox = QVBoxLayout(inner)
        vbox.setSpacing(10)
        scenarios = [
            ("S1", "客户端注册（试用）", "code=200；status=TRIAL；remainingCalls>0；返回 token；"
                                     "验证码优先取「短信验证码」输入框，其次 debugCode/环境变量；手机号留空则自动生成",
             self.runner.run_scenario_1_register),
            ("S2", "客户端登录（绑设备）", "code=200；返回 token 并完成设备绑定",
             self.runner.run_scenario_2_login),
            ("S3", "卡密核销", "code=200；套餐顺延、财务独立入账、小号独占绑定",
             self.runner.run_scenario_3_activate),
            ("S4", "加密 Token 下发", "code=200；AES-128-GCM 密文可解密出 leaseId/expire",
             self.runner.run_scenario_4_acquire),
            ("S5", "设备互踢", "替换设备头后被拦截，返回 40103",
             self.runner.run_scenario_5_kickout),
            ("S6", "成功上报扣费", "code=200；扣减 1 次；自动同步小号槽位 used_calls 与成功/失败统计",
             self.runner.run_scenario_6_report_success),
            ("S7", "故障免责拉黑", "code=200；扣 0 次；自动同步小号槽位情况（平台侧拉黑并自愈替换）",
             self.runner.run_scenario_7_report_blacklist),
            ("S8", "解绑设备", "code=200；deviceId 清空并注销会话",
             self.runner.run_scenario_8_unbind),
        ]
        for sid, name, expected, fn in scenarios:
            vbox.addWidget(ScenarioBox(sid, name, expected, self.runner, self._with_sync(fn)))
        vbox.addStretch()
        scroll.setWidget(inner)
        scroll.setWidgetResizable(True)
        return scroll

    def _boundary_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        hint = QLabel("边界测试覆盖非法输入、重复注册、错误凭证、设备不一致、卡密非法/不存在、"
                      "缺失设备头、非法业务动作、非法上报状态、空租约、未知租约、未登录解绑等。")
        hint.setWordWrap(True)
        hint.setStyleSheet("color:#475569;font-size:12px")
        layout.addWidget(hint)

        self.boundary_table = QTableWidget(0, 6)
        self.boundary_table.setHorizontalHeaderLabels(
            ["编号", "用例", "期待结果", "实际码", "结果", "说明"])
        header = self.boundary_table.horizontalHeader()
        header.setSectionResizeMode(2, QHeaderView.ResizeMode.Stretch)
        header.setSectionResizeMode(5, QHeaderView.ResizeMode.Stretch)
        layout.addWidget(self.boundary_table, 1)
        return page

    def _log_tab(self) -> QWidget:
        page = QWidget()
        vbox = QVBoxLayout(page)
        vbox.addWidget(self.log)
        return page

    # ------------------------------------------------------------------ 公共方法
    def default_value_for(self, field_name: str, fallback: str = "") -> str:
        """根据字段名返回顶部全局配置的默认值。"""
        mapping = {
            "phone": self.phone,
            "userPhone": self.phone,
            "password": self.password,
            "oldPassword": self.password,
            "deviceId": self.device_id,
        }
        edit = mapping.get(field_name)
        if edit is not None:
            return edit.text().strip() or fallback
        return fallback

    def reload_endpoint_defaults(self) -> None:
        """把当前全局配置重新同步到所有接口卡片的输入框。"""
        for card in self.endpoint_cards:
            card._apply_defaults()
            card.update_preview()
        self.log.append(f"[{time.strftime('%H:%M:%S')}] 已重载接口默认值")

    def scenario_fn(self, sid: Optional[str]) -> Optional[Callable[[], Result]]:
        mapping = {
            "S1": self.runner.run_scenario_1_register,
            "S2": self.runner.run_scenario_2_login,
            "S3": self.runner.run_scenario_3_activate,
            "S4": self.runner.run_scenario_4_acquire,
            "S5": self.runner.run_scenario_5_kickout,
            "S6": self.runner.run_scenario_6_report_success,
            "S7": self.runner.run_scenario_7_report_blacklist,
            "S8": self.runner.run_scenario_8_unbind,
        }
        return mapping.get(sid) if sid else None

    # ------------------------------------------------------------------ 动作
    def _sync_config(self) -> None:
        self.runner.client.base_url = self.base_url.text().strip().rstrip("/")
        self.runner.client.app_id = self.current_app_id()
        self.runner.device_id = self.device_id.text().strip()

    def current_app_id(self) -> int:
        return int(self.app_id.currentData() or 1)

    def _on_app_id_changed(self, _index: int) -> None:
        self.runner.client.app_id = self.current_app_id()
        self.reload_endpoint_defaults()
        self.refresh_login_state()
        self._refresh_business_info()

    def _refresh_business_info(self) -> None:
        self._sync_config()
        self.business_state.setText(f"正在读取 appId={self.current_app_id()} 的公开业务信息…")
        self.business_worker = Worker(self.runner.client.business_info)
        self.business_worker.finished.connect(self._render_business_info)
        self.business_worker.start()

    def _render_business_info(self, payload: object) -> None:
        body = payload if isinstance(payload, dict) else {}
        if body.get("code") != 200:
            self.business_state.setText(f"业务不可用：{body.get('message', '请求失败')}")
            self.business_state.setStyleSheet("color:#dc2626;font-size:12px")
            return
        data = body.get("data") or {}
        mode = "支持手机短信自助注册" if data.get("registrationMode") == "SELF_SERVICE" else "仅管理员预置账号"
        actions = ", ".join(data.get("supportedActions") or []) or "未声明"
        self.business_state.setText(
            f"{data.get('businessName', data.get('bizCode', '未知业务'))} / {data.get('bizCode')} · {mode} · "
            f"状态={data.get('effectiveStatus')}\n{data.get('businessDescription') or '暂无业务说明'}\n支持动作：{actions}"
        )
        self.send_sms_btn.setEnabled(data.get("registrationMode") == "SELF_SERVICE")
        for card in self.endpoint_cards:
            if card.endpoint.eid in {"sms_send", "register"}:
                enabled = data.get("registrationMode") == "SELF_SERVICE"
                card.send_btn.setEnabled(enabled)
                card.setToolTip("" if enabled else "该业务仅允许管理员预置账号，客户端不能自助注册")
        color = "#047857" if data.get("effectiveStatus") == "AVAILABLE" else "#d97706"
        self.business_state.setStyleSheet(f"color:{color};font-size:12px")

    def _sync_manual(self) -> None:
        """把界面手输的身份信息同步给 TestRunner（手机号/密码/短信验证码）。"""
        self._sync_config()
        self.runner.manual_phone = self.phone.text().strip()
        self.runner.manual_password = self.password.text().strip()
        self.runner.manual_sms_code = self.sms_code.text().strip()

    def _with_sync(self, fn: Callable[[], Result]) -> Callable[[], Result]:
        def wrapper() -> Result:
            self._sync_manual()
            return fn()
        return wrapper

    def refresh_login_state(self) -> None:
        sess = self.runner.client.session
        if sess.token_value and sess.phone:
            self.login_state.setText(f"已登录：{sess.phone}")
            self.login_state.setStyleSheet("color:#047857;font-weight:600")
        elif sess.phone:
            self.login_state.setText(f"未登录：{sess.phone}")
            self.login_state.setStyleSheet("color:#d97706;font-weight:600")
        else:
            self.login_state.setText("未登录")
            self.login_state.setStyleSheet("color:#dc2626;font-weight:600")

    def do_send_sms(self) -> None:
        self._sync_config()
        phone = self.phone.text().strip()
        if not phone:
            QMessageBox.warning(self, "提示", "请先填写手机号再发送验证码")
            return
        self.runner.client.expectation = "code=200（成功下发验证码）；或 42901（60 秒限频，已发的 5 分钟内仍可用）"
        body = self.runner.client.send_sms(phone, "REGISTER")
        self.append_log("sms/send", body)
        if body.get("code") == 200:
            self._start_sms_countdown()
            debug = (body.get("data") or {}).get("debugCode")
            if debug:
                self.sms_code.setText(str(debug))
                QMessageBox.information(self, "验证码", f"验证码已发送并自动回填：{debug}")
            else:
                QMessageBox.information(
                    self, "验证码",
                    "验证码已发送，但后端未回显（非 fixed-code 模式）。\n"
                    "请手动输入收到的验证码，或在后端开启 pdk.sms.local.fixed-code-enabled=true")
        elif body.get("code") == 42901:
            # 后端 60 秒限频：同一手机号+用途只允许 60 秒一条。已发的验证码 5 分钟内仍有效
            QMessageBox.information(
                self, "60 秒内已发过",
                "短信发送过于频繁：同一手机号 60 秒内只能发一条注册验证码。\n\n"
                "刚才发出的验证码在 5 分钟内仍然有效，直接填入上方输入框即可注册，无需重发。\n"
                "（若忘记验证码，请等倒计时结束后再重发）")
        else:
            QMessageBox.warning(self, "发送失败", str(body.get("message", "未知错误")))

    def _start_sms_countdown(self) -> None:
        """与后端 60 秒限频同步：发送成功后按钮倒计时 60 秒，防止重复触发 42901。"""
        self._sms_retry_after = 60
        self.send_sms_btn.setEnabled(False)
        self.send_sms_btn.setText("重发(60s)")
        self._sms_timer.start()

    def _tick_sms_countdown(self) -> None:
        self._sms_retry_after -= 1
        if self._sms_retry_after > 0:
            self.send_sms_btn.setText(f"重发({self._sms_retry_after}s)")
        else:
            self._sms_timer.stop()
            self.send_sms_btn.setEnabled(True)
            self.send_sms_btn.setText("发送验证码")

    def do_resource_status(self) -> None:
        self._sync_manual()
        if not self.runner.have_session:
            QMessageBox.warning(self, "提示", "请先登录（或先跑场景 1/2），再查询小号使用情况")
            return
        self.runner.client.expectation = "code=200 返回小号使用统计（槽位 used/allocated、用户剩余次数、成功/失败统计）"
        body = self.runner.client.resource_status()
        usage = self.runner.client.usage()
        self.append_log("resources/status", body)
        self.append_log("account/usage", usage)
        ok, text = self.runner.slot_usage_summary()
        u_ok, u_text = self.runner.usage_summary()
        QMessageBox.information(
            self, "小号使用情况",
            (text if ok else "查询小号状态失败") + "\n\n" + (u_text if u_ok else ""))

    def do_login(self) -> None:
        self._sync_config()
        phone = self.phone.text().strip()
        password = self.password.text().strip()
        if not phone or not password:
            QMessageBox.warning(self, "提示", "请填写手机号与密码")
            return
        self.runner.client.expectation = "已绑定设备 code=200；新设备未填卡 code=40380；卡密与手机号不匹配 code=40382"
        body = self.runner.client.login(phone, password, self.device_id.text().strip(), self.license_card.text().strip())
        if body.get("code") == 200:
            # 服务端权威：把输入框同步成服务端返回的 deviceId（账号级稳定标识）
            self.device_id.setText(self.runner.client.session.device_id)
            self.refresh_login_state()
            self.append_log("login", body)
        else:
            self.refresh_login_state()
            self.append_log("login", body)
            if body.get("code") == 40380:
                self.license_card.setFocus()
                QMessageBox.information(self, "需要设备卡密", "当前电脑尚未绑定许可证。请填写代理分配给此手机号的一张卡密后再次登录。")
            else:
                QMessageBox.warning(self, "登录失败", str(body.get("message", "未知错误")))

    def do_logout(self) -> None:
        self.runner.client.expectation = "code=200 注销当前会话"
        body = self.runner.client.logout()
        self.refresh_login_state()
        self.append_log("logout", body)

    def do_unbind(self) -> None:
        ans = QMessageBox.question(self, "确认解绑", "解绑后当前会话会失效，确认继续？")
        if ans != QMessageBox.StandardButton.Yes:
            return
        self.runner.client.expectation = "code=200 解绑设备并清空会话"
        body = self.runner.client.unbind_device()
        self.refresh_login_state()
        self.append_log("unbind-device", body)

    def run_all_scenarios(self) -> None:
        self._sync_manual()
        self.run_all_scenarios_btn.setEnabled(False)
        self.run_all_scenarios_btn.setText("运行中…")
        self.scenario_worker = Worker(self.runner.run_all_scenarios)
        self.scenario_worker.finished.connect(self.on_scenarios_done)
        self.scenario_worker.finished.connect(self.scenario_worker.deleteLater)
        self.scenario_worker.start()

    def on_scenarios_done(self, results: list[Result]) -> None:
        self.run_all_scenarios_btn.setEnabled(True)
        self.run_all_scenarios_btn.setText("运行全部功能场景")
        for r in results:
            self.append_log(f"{r.sid} {r.name}", {"code": r.actual_code, "message": r.actual_msg, "detail": r.detail})

    def run_all_boundary(self) -> None:
        self._sync_manual()
        self.run_all_boundary_btn.setEnabled(False)
        self.run_all_boundary_btn.setText("运行中…")
        self.boundary_worker = Worker(self.runner.run_boundary_tests)
        self.boundary_worker.finished.connect(self.on_boundary_done)
        self.boundary_worker.finished.connect(self.boundary_worker.deleteLater)
        self.boundary_worker.start()

    def on_boundary_done(self, results: list[Result]) -> None:
        self.run_all_boundary_btn.setEnabled(True)
        self.run_all_boundary_btn.setText("运行全部边界测试")
        self.boundary_table.setRowCount(len(results))
        for i, r in enumerate(results):
            if r.skipped or r.passed is None:
                verdict, color = "SKIP", QColor("#d97706")
            elif r.passed:
                verdict, color = "PASS", QColor("#047857")
            else:
                verdict, color = "FAIL", QColor("#dc2626")
            self._set_cell(i, 0, r.sid)
            self._set_cell(i, 1, r.name)
            self._set_cell(i, 2, r.expected)
            self._set_cell(i, 3, str(r.actual_code))
            item = QTableWidgetItem(verdict)
            item.setForeground(color)
            item.setFont(self.font())
            self.boundary_table.setItem(i, 4, item)
            self._set_cell(i, 5, r.detail or r.actual_msg)
            self.append_log(f"{r.sid} {r.name}", {"code": r.actual_code, "message": r.actual_msg})
        self.boundary_table.resizeRowsToContents()

    def _set_cell(self, row: int, col: int, text: str) -> None:
        self.boundary_table.setItem(row, col, QTableWidgetItem(text))

    def append_log(self, title: str, payload: object) -> None:
        stamp = time.strftime("%H:%M:%S")
        text = json.dumps(payload, ensure_ascii=False, indent=2, default=str)
        self.log.append(f"[{stamp}] {title}\n{text}\n")

    def append_http(self, rec: dict) -> None:
        """渲染单条 HTTP 调用：▶ 请求什么 / ◀ 响应什么 / 🎯 期待什么。

        由 http_log_ready 信号从任意线程安全回传，固定在本 GUI 线程执行。
        """
        ts = rec.get("ts", "")
        lines = [f"[{ts}] ▶ 请求  {rec.get('method')} {rec.get('url')}"]
        params = rec.get("params")
        reqj = rec.get("request_json")
        if reqj is not None:
            lines.append("   请求体: " + json.dumps(redact_sensitive(reqj), ensure_ascii=False, default=str))
        if params:
            lines.append("   查询参数: " + json.dumps(params, ensure_ascii=False, default=str))
        lines.append(f"   ◀ 响应  HTTP {rec.get('http_status')} | code={rec.get('code')} {rec.get('msg')}")
        lines.append("   " + json.dumps(redact_sensitive(rec.get("body")), ensure_ascii=False, default=str))
        exp = rec.get("expected")
        if exp:
            lines.append(f"   🎯 期待  {exp}")
        lines.append("")  # 分隔空行
        self.log.append("\n".join(lines))


def main() -> int:
    app = QApplication([])
    app.setStyle("Fusion")
    window = MainWindow()
    updates = ClientUpdateManager(window.runner.client, window.runner.build_config, window.runner.device_id)
    checking = QProgressDialog("正在登录前检查客户端更新…", "", 0, 0)
    checking.setWindowTitle("PDK 客户端升级")
    checking.setCancelButton(None)
    checking.show()
    app.processEvents()
    try:
        decision = updates.check()
    except UpdateError as exc:
        decision = updates.cached_required()
        if decision is None:
            QMessageBox.warning(None, "更新检查暂不可用", f"{exc}\n\n当前没有仍生效的已验签强制策略，将继续启动。")
    finally:
        checking.close()
    if decision and decision.get("hasUpdate"):
        required = decision.get("updatePolicy") == "REQUIRED"
        buttons = QMessageBox.StandardButton.Yes if required else (QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No)
        answer = QMessageBox.question(None, "必须更新" if required else "发现新版本",
            f"当前版本：{updates.current_version}\n目标版本：{decision.get('targetVersion')}\n\n{decision.get('releaseNotes') or '包含稳定性与安全更新'}\n\n{'必须完成更新后才能继续。' if required else '是否立即更新？'}",
            buttons, QMessageBox.StandardButton.Yes)
        if answer == QMessageBox.StandardButton.Yes:
            progress = QProgressDialog("正在下载并验证升级包…", "取消" if not required else "", 0, 100)
            progress.setWindowTitle("客户端升级")
            if required: progress.setCancelButton(None)
            progress.show()
            try:
                package = updates.download_and_verify(decision, lambda done,total: (progress.setValue(min(100,int(done*100/total))), app.processEvents()))
                updates.launch_updater(decision, package)
                return 0
            except UpdateError as exc:
                QMessageBox.critical(None, "更新失败", str(exc))
                if required: return 2
            finally: progress.close()
        elif required:
            return 2
    window.show()
    health_file = os.getenv("PDK_UPDATE_HEALTH_FILE", "")
    if health_file:
        try:
            from pathlib import Path
            Path(health_file).write_text("ok", encoding="utf-8")
        except OSError:
            pass
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
