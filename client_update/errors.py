"""升级模块异常类型。"""


class UpdateError(RuntimeError):
    """可安全展示给最终用户的升级错误。"""


class UpdateCancelled(UpdateError):
    """用户主动取消可选升级。"""
