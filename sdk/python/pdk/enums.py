"""PDK SDK 跨语言统一枚举（整数值与 C++ / 易语言 完全一致）。"""
from __future__ import annotations

from enum import IntEnum


class State(IntEnum):
    """连接状态。"""

    Uninitialized = 0
    Ready = 1
    SmsSent = 2
    Registering = 3
    Registered = 4
    LoggingIn = 5
    LoggedIn = 6
    LoggingOut = 7
    LoggedOut = 8
    DeviceUnbound = 9
    TokenAcquiring = 10
    TokenAcquired = 11
    TokenFailed = 12
    ResultReporting = 13
    Kicked = 14
    Error = 15
    DeviceLicenseRequired = 16
    LicenseActive = 17
    LicenseExpired = 18
    LicenseSuspended = 19
    LicenseRevoked = 20


class Event(IntEnum):
    """细粒度事件。"""

    None_ = 0
    RequestSent = 100
    ResponseReceived = 101
    DecryptSucceeded = 102
    DecryptFailed = 103
    QuotaLow = 104
    SubscriptionExpired = 105
    QuotaExhausted = 106
    NoAvailableToken = 107


class ResultCode:
    """业务返回码（与后端 CommonResult.code 一致）。"""

    SUCCESS = 200
    CARD_NOT_FOUND = 40001
    CARD_ALREADY_USED = 40002
    CONCURRENT_CONFLICT = 40004
    TRIAL_ALREADY_CLAIMED = 40010
    MISSING_AUTH_HEADERS = 40101
    DEVICE_KICK_OUT = 40103
    SUBSCRIPTION_EXPIRED = 40301
    QUOTA_EXHAUSTED = 40302
    DEVICE_LICENSE_REQUIRED = 40380
    DEVICE_LICENSE_EXPIRED = 40381
    CARD_NOT_ASSIGNED_TO_USER = 40382
    CARD_BOUND_OTHER_DEVICE = 40383
    DEVICE_LICENSE_UNAVAILABLE = 40384
    DEVICE_LICENSE_MISSING = 40385
    DEVICE_ALREADY_HAS_LICENSE = 40980
    LICENSE_BIND_CONFLICT = 40981
    RENEWAL_IDEMPOTENCY_CONFLICT = 40982
    NO_AVAILABLE_TOKEN = 50301
    NETWORK_ERROR = 0
