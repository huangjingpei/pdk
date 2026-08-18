from __future__ import annotations

import base64
import hashlib
import json
import platform
import os
import sys
import time
import uuid
from dataclasses import dataclass
from typing import Any

import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PyQt6.QtCore import Qt, QRectF
from PyQt6.QtGui import QColor, QPainter, QPen
from PyQt6.QtWidgets import (
    QApplication,
    QComboBox,
    QFormLayout,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QStackedWidget,
    QTabWidget,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)


ROOT_SALT = os.getenv("PDK_SECURITY_ROOT_SALT", "PDK_SECRET_SALT_2026_ENTERPRISE")


class ApiError(RuntimeError):
    pass


def default_device_id() -> str:
    source = f"{platform.node()}:{uuid.getnode()}:{platform.system()}"
    digest = hashlib.sha256(source.encode("utf-8")).hexdigest()[:24].upper()
    return f"PYQT-{digest}"


def decrypt_payload(payload: str) -> dict[str, Any]:
    raw = base64.b64decode(payload)[::-1]
    if len(raw) < 30 or raw[:2] != b"PD":
        raise ValueError("不是有效的 PDK 加密报文")
    iv, ciphertext = raw[2:14], raw[14:]
    current_window = int(time.time() // 60 // 10)
    for window in (current_window, current_window - 1, current_window + 1):
        key = hashlib.sha256(f"{ROOT_SALT}_{window}".encode()).digest()[:16]
        try:
            plaintext = AESGCM(key).decrypt(iv, ciphertext, None)
            return json.loads(plaintext.decode("utf-8"))
        except Exception:
            continue
    raise ValueError("解密失败：时间窗口过期或数据损坏")


@dataclass
class ClientSession:
    token_name: str = "satoken"
    token_value: str = ""
    phone: str = ""
    device_id: str = ""
    password: str = ""


class PdkApiClient:
    def __init__(self) -> None:
        self.base_url = "http://localhost:8080"
        self.session = ClientSession()
        self.http = requests.Session()

    def configure(self, base_url: str, phone: str, device_id: str, password: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.session.phone = phone.strip()
        self.session.device_id = device_id.strip()
        self.session.password = password

    def request(self, method: str, path: str, *, authenticated: bool = False, **kwargs: Any) -> Any:
        headers = dict(kwargs.pop("headers", {}))
        if authenticated:
            if not self.session.token_value:
                raise ApiError("请先登录")
            headers[self.session.token_name] = self.session.token_value
            headers["X-PDK-Phone"] = self.session.phone
            headers["X-PDK-Device-ID"] = self.session.device_id
        try:
            response = self.http.request(
                method, f"{self.base_url}{path}", headers=headers, timeout=15, **kwargs
            )
            response.raise_for_status()
            body = response.json()
        except requests.RequestException as exc:
            raise ApiError(f"网络请求失败: {exc}") from exc
        except ValueError as exc:
            raise ApiError("服务端返回的不是 JSON") from exc
        if body.get("code") != 200:
            raise ApiError(f"[{body.get('code')}] {body.get('message', '请求失败')}")
        return body.get("data")

    def login(self) -> Any:
        data = self.request("POST", "/api/v1/client/auth/login", json={
            "phone": self.session.phone, "deviceId": self.session.device_id, "password": self.session.password,
        })
        self.session.token_name = data["tokenName"]
        self.session.token_value = data["tokenValue"]
        return data

    def register(self, sms_code: str, invitation_code: str = "") -> Any:
        data = self.request("POST", "/api/v1/client/auth/register", json={
            "phone": self.session.phone, "deviceId": self.session.device_id,
            "password": self.session.password, "smsCode": sms_code,
            "invitationCode": invitation_code or None,
        })
        self.session.token_name = data["tokenName"]
        self.session.token_value = data["tokenValue"]
        return data

    def activate_card(self, card_key: str) -> Any:
        return self.request("POST", "/api/v1/card/activate", json={
            "cardKey": card_key.strip(), "userPhone": self.session.phone,
            "deviceId": self.session.device_id, "orderType": "NORMAL_SALE",
            "paymentChannel": "OFFLINE",
        })

    def clear_token(self) -> None:
        self.session.token_value = ""


class CloudLogo(QWidget):
    def paintEvent(self, event: Any) -> None:
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.setPen(QPen(QColor("#2473ba"), 7, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
        w, h = self.width(), self.height()
        path = __import__('PyQt6.QtGui', fromlist=['QPainterPath']).QPainterPath()
        path.moveTo(w * .25, h * .63)
        path.cubicTo(w * .12, h * .63, w * .12, h * .43, w * .27, h * .41)
        path.cubicTo(w * .27, h * .25, w * .47, h * .19, w * .58, h * .34)
        path.cubicTo(w * .72, h * .28, w * .83, h * .39, w * .82, h * .50)
        path.cubicTo(w * .82, h * .59, w * .74, h * .63, w * .65, h * .63)
        path.closeSubpath()
        painter.drawPath(path)


class AuthWindow(QWidget):
    def __init__(self) -> None:
        super().__init__()
        self.api = PdkApiClient()
        self.main_window: MainWindow | None = None
        self.device_id = default_device_id()
        self.setWindowTitle("云朵")
        self.setFixedSize(1040, 660)
        self.setStyleSheet("QWidget{font-family:'Microsoft YaHei';color:#252a30} QLineEdit{height:38px;border:1px solid #d8dee8;border-radius:6px;padding:0 12px;font-size:14px} QPushButton#primary{height:40px;background:#3785d3;color:white;border:0;border-radius:6px;font-weight:600} QPushButton#primary:hover{background:#2473ba} QPushButton#tab{border:0;background:transparent;padding:8px 4px;font-size:14px} QPushButton#tab:checked{color:#1677d2;border-bottom:2px solid #1677d2}")
        root = QHBoxLayout(self); root.setContentsMargins(0, 0, 0, 0); root.setSpacing(0)
        brand = QWidget(); brand.setObjectName("brandPanel"); brand.setStyleSheet("QWidget#brandPanel{background:#fcfbfa}")
        brand_layout = QVBoxLayout(brand); brand_layout.setContentsMargins(70, 70, 70, 70)
        logo = CloudLogo(); logo.setMinimumSize(350, 350); brand_layout.addStretch(); brand_layout.addWidget(logo); brand_layout.addStretch()
        root.addWidget(brand, 48)
        panel = QWidget(); panel.setObjectName("authPanel"); panel.setStyleSheet("QWidget#authPanel{background:white}")
        panel_layout = QVBoxLayout(panel); panel_layout.setContentsMargins(130, 105, 130, 70)
        title = QLabel("云朵"); title.setAlignment(Qt.AlignmentFlag.AlignCenter); title.setStyleSheet("font-size:30px;font-weight:700;margin-bottom:8px")
        panel_layout.addWidget(title)
        tabs = QHBoxLayout(); self.tab_buttons: list[QPushButton] = []
        for index, text_value in enumerate(("登录", "注册", "卡密兑换", "修改密码")):
            button = QPushButton(text_value); button.setObjectName("tab"); button.setCheckable(True)
            button.clicked.connect(lambda checked, i=index: self.select_tab(i)); tabs.addWidget(button); self.tab_buttons.append(button)
        panel_layout.addLayout(tabs)
        self.pages = QStackedWidget(); panel_layout.addWidget(self.pages)
        self._build_login(); self._build_register(); self._build_card(); self._build_password()
        version = QLabel("当前版本：1.8.0"); version.setAlignment(Qt.AlignmentFlag.AlignCenter); version.setStyleSheet("font-size:16px;font-weight:600")
        panel_layout.addWidget(version); panel_layout.addStretch()
        root.addWidget(panel, 52); self.select_tab(0)

    def _page(self) -> tuple[QWidget, QVBoxLayout]:
        page = QWidget(); layout = QVBoxLayout(page); layout.setContentsMargins(0, 22, 0, 8); layout.setSpacing(12); return page, layout

    def _input(self, placeholder: str, password: bool = False) -> QLineEdit:
        field = QLineEdit(); field.setPlaceholderText(placeholder)
        if password: field.setEchoMode(QLineEdit.EchoMode.Password)
        return field

    def _primary(self, text_value: str, action: Any) -> QPushButton:
        button = QPushButton(text_value); button.setObjectName("primary"); button.clicked.connect(action); return button

    def _build_login(self) -> None:
        page, layout = self._page(); self.login_phone = self._input("请输入手机号"); self.login_password = self._input("请输入密码", True)
        layout.addWidget(self.login_phone); layout.addWidget(self.login_password); layout.addWidget(self._primary("登录", self.do_login)); layout.addStretch(); self.pages.addWidget(page)

    def _build_register(self) -> None:
        page, layout = self._page(); self.reg_phone = self._input("手机号"); self.reg_password = self._input("密码（至少8位）", True)
        self.reg_invite = self._input("邀请码（选填，用于绑定邀请代理）"); self.reg_card = self._input("卡密（选填，注册后自动激活）")
        sms_row = QHBoxLayout(); self.reg_sms = self._input("短信验证码"); send = QPushButton("发送验证码"); send.setFixedHeight(40); send.clicked.connect(self.send_register_sms); sms_row.addWidget(self.reg_sms, 1); sms_row.addWidget(send)
        for field in (self.reg_phone, self.reg_password, self.reg_invite, self.reg_card): layout.addWidget(field)
        layout.addLayout(sms_row); layout.addWidget(self._primary("注册", self.do_register)); layout.addStretch(); self.pages.addWidget(page)

    def _build_card(self) -> None:
        page, layout = self._page(); self.card_phone = self._input("请输入已注册手机号"); self.card_value = self._input("请输入卡密")
        layout.addWidget(self.card_phone); layout.addWidget(self.card_value); layout.addWidget(self._primary("立即兑换", self.do_card)); layout.addStretch(); self.pages.addWidget(page)

    def _build_password(self) -> None:
        page, layout = self._page(); self.pwd_phone = self._input("手机号"); self.old_password = self._input("旧密码", True); self.new_password = self._input("新密码（至少8位）", True)
        for field in (self.pwd_phone, self.old_password, self.new_password): layout.addWidget(field)
        layout.addWidget(self._primary("修改", self.do_change_password)); layout.addStretch(); self.pages.addWidget(page)

    def select_tab(self, index: int) -> None:
        self.pages.setCurrentIndex(index)
        for i, button in enumerate(self.tab_buttons): button.setChecked(i == index)

    def _configure(self, phone: str, password: str = "") -> None:
        self.api.configure("http://localhost:8080", phone, self.device_id, password)

    def _call(self, action: Any) -> Any:
        try: return action()
        except Exception as exc: QMessageBox.warning(self, "操作失败", str(exc)); return None

    def send_register_sms(self) -> None:
        phone = self.reg_phone.text().strip()
        data = self._call(lambda: self.api.request("POST", "/api/v1/client/auth/sms/send", json={"phone": phone, "purpose": "REGISTER"}))
        if data and data.get("debugCode"): self.reg_sms.setText(data["debugCode"])
        if data: QMessageBox.information(self, "验证码", "验证码已发送")

    def do_login(self) -> None:
        self._configure(self.login_phone.text(), self.login_password.text()); data = self._call(self.api.login)
        if data: self.open_main()

    def do_register(self) -> None:
        self._configure(self.reg_phone.text(), self.reg_password.text())
        data = self._call(lambda: self.api.register(self.reg_sms.text().strip(), self.reg_invite.text().strip()))
        if not data: return
        card = self.reg_card.text().strip()
        if card:
            activated = self._call(lambda: self.api.activate_card(card))
            if activated is None: QMessageBox.information(self, "注册成功", "账号已注册，但卡密激活失败，可稍后在卡密兑换页重试。")
        self.open_main()

    def do_card(self) -> None:
        self._configure(self.card_phone.text(), "")
        if self._call(lambda: self.api.activate_card(self.card_value.text())) is not None: QMessageBox.information(self, "兑换成功", "卡密套餐已激活，请返回登录。")

    def do_change_password(self) -> None:
        data = self._call(lambda: self.api.request("POST", "/api/v1/client/auth/change-password", json={"phone": self.pwd_phone.text().strip(), "oldPassword": self.old_password.text(), "newPassword": self.new_password.text()}))
        if data is not None: QMessageBox.information(self, "修改成功", "密码已修改，请使用新密码登录"); self.select_tab(0)

    def open_main(self) -> None:
        self.main_window = MainWindow(self.api); self.main_window.show(); self.close()


class MainWindow(QMainWindow):
    def __init__(self, api: PdkApiClient | None = None) -> None:
        super().__init__()
        self.api = api or PdkApiClient()
        self.current_lease_id = ""
        self.setWindowTitle("PDK 客户端接口联调 Demo")
        self.resize(980, 720)

        root = QWidget()
        layout = QVBoxLayout(root)
        layout.addWidget(self._connection_box())
        self.tabs = QTabWidget()
        self.tabs.addTab(self._activation_tab(), "手机注册与卡密激活")
        self.tabs.addTab(self._resource_tab(), "小号资源与结果")
        self.tabs.addTab(self._usage_tab(), "次数与使用记录")
        layout.addWidget(self.tabs, 1)
        self.log = QTextEdit()
        self.log.setReadOnly(True)
        self.log.setMaximumHeight(190)
        self.log.setPlaceholderText("接口响应日志")
        layout.addWidget(self.log)
        self.setCentralWidget(root)

    def _connection_box(self) -> QGroupBox:
        box = QGroupBox("服务与客户端身份")
        grid = QGridLayout(box)
        self.base_url = QLineEdit(self.api.base_url)
        self.phone = QLineEdit(self.api.session.phone or "13800138000")
        self.device_id = QLineEdit(self.api.session.device_id or default_device_id())
        self.password = QLineEdit(self.api.session.password)
        self.password.setEchoMode(QLineEdit.EchoMode.Password)
        self.login_state = QLabel("未登录")
        self.login_state.setStyleSheet("color:#b91c1c;font-weight:600")
        login_button = QPushButton("登录")
        login_button.clicked.connect(self.login)
        logout_button = QPushButton("注销会话")
        logout_button.clicked.connect(self.logout)
        unbind_button = QPushButton("解绑电脑")
        unbind_button.clicked.connect(self.unbind)
        grid.addWidget(QLabel("服务地址"), 0, 0)
        grid.addWidget(self.base_url, 0, 1)
        grid.addWidget(QLabel("手机号"), 0, 2)
        grid.addWidget(self.phone, 0, 3)
        grid.addWidget(QLabel("登录密码"), 1, 0)
        grid.addWidget(self.password, 1, 1)
        grid.addWidget(QLabel("设备ID"), 2, 0)
        grid.addWidget(self.device_id, 2, 1, 1, 3)
        buttons = QHBoxLayout()
        buttons.addWidget(login_button)
        buttons.addWidget(logout_button)
        buttons.addWidget(unbind_button)
        buttons.addWidget(self.login_state)
        buttons.addStretch()
        grid.addLayout(buttons, 3, 0, 1, 4)
        return box

    def _activation_tab(self) -> QWidget:
        page = QWidget()
        form = QFormLayout(page)
        self.card_key = QLineEdit("PDK-XXXX-XXXX-XXXX")
        activate = QPushButton("激活卡密")
        activate.clicked.connect(self.activate)
        form.addRow(QLabel("当前账号可在此激活新的付费套餐卡密。注册、兑换和修改密码请在登录入口完成。"))
        form.addRow("套餐卡密", self.card_key)
        form.addRow(activate)
        return page

    def _resource_tab(self) -> QWidget:
        page = QWidget()
        form = QFormLayout(page)
        self.action_type = QComboBox()
        self.action_type.addItems(["GOODS_COLLECT", "ORDER_PULL", "DETAIL_QUERY"])
        self.goods_id = QLineEdit("881920391204")
        acquire = QPushButton("领取短效小号资源")
        acquire.clicked.connect(self.acquire_resource)
        self.lease_label = QLabel("尚未领取")
        self.result_status = QComboBox()
        self.result_status.addItems(["SUCCESS", "FAIL_ACCOUNT_BANNED", "FAIL_NETWORK", "FAIL_BUSINESS"])
        self.error_message = QLineEdit()
        report = QPushButton("上报本次使用结果")
        report.clicked.connect(self.report_result)
        status = QPushButton("查询资源可用状态")
        status.clicked.connect(self.resource_status)
        form.addRow("业务动作", self.action_type)
        form.addRow("商品/业务ID", self.goods_id)
        form.addRow(acquire)
        form.addRow("当前租约", self.lease_label)
        form.addRow("使用结果", self.result_status)
        form.addRow("错误说明", self.error_message)
        form.addRow(report)
        form.addRow(status)
        return page

    def _usage_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        buttons = QHBoxLayout()
        profile = QPushButton("查询账号与剩余次数")
        profile.clicked.connect(self.profile)
        usage = QPushButton("查询成功/失败次数及明细")
        usage.clicked.connect(self.usage)
        buttons.addWidget(profile)
        buttons.addWidget(usage)
        buttons.addStretch()
        self.usage_text = QTextEdit()
        self.usage_text.setReadOnly(True)
        layout.addLayout(buttons)
        layout.addWidget(self.usage_text)
        return page

    def configure(self) -> None:
        self.api.configure(self.base_url.text(), self.phone.text(), self.device_id.text(), self.password.text())

    def run_api(self, action: Any) -> Any:
        self.configure()
        try:
            data = action()
            self.write_log(data)
            return data
        except Exception as exc:
            self.write_log({"error": str(exc)})
            QMessageBox.warning(self, "接口调用失败", str(exc))
            return None

    def write_log(self, data: Any) -> None:
        stamp = time.strftime("%H:%M:%S")
        self.log.append(f"[{stamp}] {json.dumps(data, ensure_ascii=False, indent=2, default=str)}")

    def login(self) -> None:
        data = self.run_api(self.api.login)
        if data:
            self.login_state.setText(f"已登录：{data.get('packageName') or '未激活套餐'}")
            self.login_state.setStyleSheet("color:#047857;font-weight:600")

    def logout(self) -> None:
        data = self.run_api(lambda: self.api.request("POST", "/api/v1/client/auth/logout", authenticated=True))
        if data is not None:
            self.api.clear_token()
            self.login_state.setText("已注销（电脑仍绑定）")

    def unbind(self) -> None:
        answer = QMessageBox.question(self, "确认解绑", "解绑后当前会话会失效，确认继续？")
        if answer != QMessageBox.StandardButton.Yes:
            return
        data = self.run_api(lambda: self.api.request("POST", "/api/v1/client/auth/unbind-device", authenticated=True))
        if data is not None:
            self.api.clear_token()
            self.login_state.setText("已解绑电脑")

    def activate(self) -> None:
        self.run_api(lambda: self.api.request("POST", "/api/v1/card/activate", json={
            "cardKey": self.card_key.text().strip(),
            "userPhone": self.phone.text().strip(),
            "deviceId": self.device_id.text().strip(),
            "orderType": "NORMAL_SALE",
            "paymentChannel": "OFFLINE",
        }))

    def send_sms(self) -> None:
        data = self.run_api(lambda: self.api.request("POST", "/api/v1/client/auth/sms/send", json={
            "phone": self.phone.text().strip(), "purpose": "REGISTER",
        }))
        if data and data.get("debugCode"):
            self.sms_code.setText(data["debugCode"])

    def register_user(self) -> None:
        data = self.run_api(lambda: self.api.register(self.sms_code.text().strip()))
        if data:
            if data.get("resourceAllocated", True):
                self.login_state.setText("注册成功并已登录（CUSTOMER），试用小号已分配")
                self.login_state.setStyleSheet("color:#047857;font-weight:600")
            else:
                self.login_state.setText("注册成功并已登录；小号库存不足，等待平台补充")
                self.login_state.setStyleSheet("color:#b45309;font-weight:600")
                QMessageBox.information(self, "注册成功", data.get("resourceMessage", "小号资源等待平台补充"))

    def acquire_resource(self) -> None:
        data = self.run_api(lambda: self.api.request("POST", "/api/v1/client/resources/acquire", authenticated=True, json={
            "actionType": self.action_type.currentText(),
            "goodsId": self.goods_id.text().strip(),
            "timestamp": int(time.time() * 1000),
        }))
        if data:
            self.current_lease_id = data["leaseTraceId"]
            self.lease_label.setText(self.current_lease_id)
            try:
                self.write_log({"decryptedPayload": decrypt_payload(data["encryptedPayload"])})
            except Exception as exc:
                self.write_log({"decryptError": str(exc)})

    def report_result(self) -> None:
        if not self.current_lease_id:
            QMessageBox.information(self, "无租约", "请先领取短效小号资源")
            return
        data = self.run_api(lambda: self.api.request("POST", "/api/v1/client/resources/report", authenticated=True, json={
            "leaseTraceId": self.current_lease_id,
            "status": self.result_status.currentText(),
            "responseDurationMs": 1000,
            "errorMessage": self.error_message.text().strip(),
        }))
        if data is not None:
            self.current_lease_id = ""
            self.lease_label.setText("已上报")

    def resource_status(self) -> None:
        self.run_api(lambda: self.api.request("GET", "/api/v1/client/resources/status", authenticated=True))

    def profile(self) -> None:
        data = self.run_api(lambda: self.api.request("GET", "/api/v1/client/account/profile", authenticated=True))
        if data:
            self.usage_text.setPlainText(json.dumps(data, ensure_ascii=False, indent=2, default=str))

    def usage(self) -> None:
        data = self.run_api(lambda: self.api.request("GET", "/api/v1/client/account/usage", authenticated=True))
        if data:
            self.usage_text.setPlainText(json.dumps(data, ensure_ascii=False, indent=2, default=str))


def main() -> int:
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    window = AuthWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
