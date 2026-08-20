"""PDK 全链路测试工作台（PyQt6 GUI）。

把 docs/TESTING_GUIDE.md 里的 8 个功能场景与边界测试做成可点击验证的界面：
  - 顶部：连接与客户端身份（地址 / 手机号 / 设备ID / 密码 / 登录态）
  - 「功能场景」页：8 个场景卡片，每个显示「期待结果 / 实际结果 / PASS·FAIL·SKIP」+ 原始报文
  - 「边界测试」页：一键运行 16 个边界用例，表格汇总
  - 「响应日志」页：所有请求的原始 JSON

底层逻辑全部复用 pdk_testrunner.TestRunner，与命令行 run_tests.py 完全一致。
"""
from __future__ import annotations

import json
import threading
import time
from typing import Callable, Optional

from PyQt6.QtCore import Qt, QThread, QTimer, pyqtSignal
from PyQt6.QtGui import QColor
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
    QPushButton,
    QScrollArea,
    QTabWidget,
    QTableWidget,
    QTableWidgetItem,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from pdk_client import default_device_id
from pdk_testrunner import Result, TestRunner


class Worker(QThread):
    """在后台线程跑测试，避免界面卡死。"""

    finished = pyqtSignal(object)  # Result 或 list[Result]

    def __init__(self, fn: Callable[[], object]) -> None:
        super().__init__()
        self.fn = fn

    def run(self) -> None:  # noqa: D401
        try:
            self.finished.emit(self.fn())
        except Exception as exc:  # noqa: BLE001
            self.finished.emit(Result(
                sid="ERR", name="执行异常", category="", expected="",
                actual_code=0, actual_msg=str(exc), passed=False, detail=str(exc),
            ))


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


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.runner = TestRunner(device_id=default_device_id())
        self.log = QTextEdit()
        self.log.setReadOnly(True)
        # 短信 60 秒限频倒计时（与后端 SmsCodeService 的 60s 限频一致）
        self._sms_retry_after = 0
        self._sms_timer = QTimer(self)
        self._sms_timer.setInterval(1000)
        self._sms_timer.timeout.connect(self._tick_sms_countdown)
        self.init_ui()

    # ------------------------------------------------------------------ UI
    def init_ui(self) -> None:
        self.setWindowTitle("PDK 全链路测试工作台")
        self.resize(1080, 760)
        root = QWidget()
        layout = QVBoxLayout(root)
        layout.setSpacing(10)

        layout.addWidget(self._connection_box())

        top_row = QHBoxLayout()
        self.run_all_scenarios_btn = QPushButton("运行全部功能场景")
        self.run_all_scenarios_btn.clicked.connect(self.run_all_scenarios)
        self.run_all_boundary_btn = QPushButton("运行全部边界测试")
        self.run_all_boundary_btn.clicked.connect(self.run_all_boundary)
        top_row.addWidget(self.run_all_scenarios_btn)
        top_row.addWidget(self.run_all_boundary_btn)
        top_row.addStretch()
        layout.addLayout(top_row)

        tabs = QTabWidget()
        tabs.addTab(self._scenarios_tab(), "功能场景（8）")
        tabs.addTab(self._boundary_tab(), "边界测试（16）")
        tabs.addTab(self._log_tab(), "响应日志")
        layout.addWidget(tabs, 1)
        self.setCentralWidget(root)

    def _connection_box(self) -> QGroupBox:
        box = QGroupBox("连接与客户端身份")
        grid = QGridLayout(box)
        self.base_url = QLineEdit(self.runner.client.base_url)
        self.phone = QLineEdit("")
        self.password = QLineEdit("")
        self.password.setEchoMode(QLineEdit.EchoMode.Password)
        self.device_id = QLineEdit(self.runner.device_id)
        self.sms_code = QLineEdit("")
        self.sms_code.setPlaceholderText("注册用验证码；fixed-code 模式点「发送验证码」自动回填")
        self.login_state = QLabel("未登录")
        self.login_state.setStyleSheet("color:#dc2626;font-weight:600")

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
        grid.addWidget(QLabel("手机号"), 0, 2)
        grid.addWidget(self.phone, 0, 3)
        grid.addWidget(QLabel("登录密码"), 1, 0)
        grid.addWidget(self.password, 1, 1)
        grid.addWidget(QLabel("设备ID"), 1, 2)
        grid.addWidget(self.device_id, 1, 3)
        grid.addWidget(QLabel("短信验证码"), 2, 0)
        grid.addWidget(self.sms_code, 2, 1)
        grid.addWidget(self.send_sms_btn, 2, 2)
        grid.addWidget(slots_btn, 2, 3)
        btn_row = QHBoxLayout()
        btn_row.addWidget(login)
        btn_row.addWidget(logout)
        btn_row.addWidget(unbind)
        btn_row.addWidget(self.login_state)
        btn_row.addStretch()
        grid.addLayout(btn_row, 3, 0, 1, 4)
        return box

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
            ("S6", "成功上报扣费", "code=200；扣 1 次；自动同步小号槽位 used_calls 与成功/失败统计",
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

    # ------------------------------------------------------------------ 动作
    def _sync_config(self) -> None:
        self.runner.client.base_url = self.base_url.text().strip().rstrip("/")
        self.runner.device_id = self.device_id.text().strip()

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

    def do_send_sms(self) -> None:
        self._sync_config()
        phone = self.phone.text().strip()
        if not phone:
            QMessageBox.warning(self, "提示", "请先填写手机号再发送验证码")
            return
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
        body = self.runner.client.login(phone, password, self.device_id.text().strip())
        if body.get("code") == 200:
            self.login_state.setText(f"已登录：{phone}")
            self.login_state.setStyleSheet("color:#047857;font-weight:600")
            self.append_log("login", body)
        else:
            self.login_state.setText("登录失败")
            self.login_state.setStyleSheet("color:#dc2626;font-weight:600")
            self.append_log("login", body)
            QMessageBox.warning(self, "登录失败", str(body.get("message", "未知错误")))

    def do_logout(self) -> None:
        body = self.runner.client.logout()
        self.login_state.setText("已注销（电脑仍绑定）")
        self.login_state.setStyleSheet("color:#d97706;font-weight:600")
        self.append_log("logout", body)

    def do_unbind(self) -> None:
        ans = QMessageBox.question(self, "确认解绑", "解绑后当前会话会失效，确认继续？")
        if ans != QMessageBox.StandardButton.Yes:
            return
        body = self.runner.client.unbind_device()
        self.login_state.setText("已解绑设备")
        self.login_state.setStyleSheet("color:#dc2626;font-weight:600")
        self.append_log("unbind-device", body)

    def run_all_scenarios(self) -> None:
        self._sync_manual()
        self.run_all_scenarios_btn.setEnabled(False)
        self.run_all_scenarios_btn.setText("运行中…")
        worker = Worker(self.runner.run_all_scenarios)
        worker.finished.connect(self.on_scenarios_done)
        worker.start()

    def on_scenarios_done(self, results: list[Result]) -> None:
        self.run_all_scenarios_btn.setEnabled(True)
        self.run_all_scenarios_btn.setText("运行全部功能场景")
        for r in results:
            self.append_log(f"{r.sid} {r.name}", {"code": r.actual_code, "message": r.actual_msg, "detail": r.detail})

    def run_all_boundary(self) -> None:
        self._sync_manual()
        self.run_all_boundary_btn.setEnabled(False)
        self.run_all_boundary_btn.setText("运行中…")
        worker = Worker(self.runner.run_boundary_tests)
        worker.finished.connect(self.on_boundary_done)
        worker.start()

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


def main() -> int:
    app = QApplication([])
    app.setStyle("Fusion")
    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
