"""PDK 客户端 Python SDK。

统一回调模型（与 C++ / 易语言 一致）：
  - on_state(state, detail)   连接状态变化（未登录 / 登录中 / 已登录 / 被踢 ...）
  - on_event(event, message)  细粒度事件（解密成功 / 配额耗尽 ...）
  - on_log(line)              每条 HTTP 的 ▶请求/◀响应/🎯期待 调试日志

示例见 examples/demo.py。
"""
from __future__ import annotations

from typing import Callable, Optional

from .client import PdkApiClient
from .crypto import decrypt_payload, derive_key
from .enums import Event, ResultCode, State

__all__ = [
    "PdkApiClient",
    "State",
    "Event",
    "ResultCode",
    "decrypt_payload",
    "derive_key",
]

# 给调用方一个便捷别名（与 on_state/on_event 同风格）
StateCallback = Callable[[State, str], None]
EventCallback = Callable[[Event, str], None]
LogCallback = Callable[[str], None]
