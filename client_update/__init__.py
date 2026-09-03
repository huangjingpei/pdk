"""可复用的 PDK 桌面客户端升级组件。

业务程序只需提供 ``client-update.json``，并在启动入口调用 Qt 编排层；网络协议、
策略/构件验签、断点下载和安装回滚均封装在本包中。
"""

from .config import UpdateConfig
from .errors import UpdateError
from .health import mark_update_healthy
from .manager import ClientUpdateManager

__all__ = ["ClientUpdateManager", "UpdateConfig", "UpdateError", "mark_update_healthy"]
