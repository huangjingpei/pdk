"""PDK 客户端升级公开 API 适配器，不依赖登录会话。"""
from __future__ import annotations

from datetime import datetime
from typing import Any

import requests

from .config import UpdateConfig
from .errors import UpdateError


class UpdateApiClient:
    def __init__(self, config: UpdateConfig, device_id: str, session: requests.Session | None = None) -> None:
        self.config = config
        self.device_id = device_id.strip()
        self.http = session or requests.Session()
        self._owns_session = session is None

    @property
    def headers(self) -> dict[str, str]:
        return {
            "X-PDK-App-ID": str(self.config.app_id),
            "X-PDK-Device-ID": self.device_id,
            "User-Agent": f"{self.config.biz_code or 'PDK-Desktop'}/{self.config.version}",
        }

    def check(self) -> dict[str, Any]:
        try:
            response = self.http.get(
                self.config.server_base_url + "/api/v1/client/updates/check",
                headers=self.headers,
                params={
                    "currentVersion": self.config.version,
                    "platform": self.config.platform,
                    "arch": self.config.arch,
                    "channel": self.config.channel,
                    "protocolVersion": self.config.protocol_version,
                    "updaterVersion": self.config.updater_version,
                },
                timeout=(5, 15),
            )
            payload = response.json()
        except (requests.RequestException, ValueError) as exc:
            raise UpdateError(f"连接升级服务器失败：{exc}") from exc
        if response.status_code != 200 or not isinstance(payload, dict) or payload.get("code") != 200:
            message = payload.get("message") if isinstance(payload, dict) else response.reason
            raise UpdateError(f"升级检查失败：{message or response.status_code}")
        data = payload.get("data")
        if not isinstance(data, dict):
            raise UpdateError("升级服务器返回的数据格式不正确")
        return data

    def report(self, decision: dict[str, Any], event_type: str, error_category: str | None = None) -> None:
        artifact = decision.get("artifact") or {}
        payload = {
            "checkRequestId": decision.get("checkRequestId"),
            "eventToken": decision.get("eventToken"),
            "artifactId": artifact.get("artifactId"),
            "eventType": event_type,
            "fromVersion": self.config.version,
            "targetVersion": decision.get("targetVersion"),
            "platform": self.config.platform,
            "errorCategory": error_category,
            "clientTime": datetime.now().isoformat(timespec="seconds"),
        }
        try:
            self.http.post(
                self.config.server_base_url + "/api/v1/client/updates/events",
                headers=self.headers, json=payload, timeout=(3, 5),
            )
        except requests.RequestException:
            pass

    def close(self) -> None:
        if self._owns_session:
            self.http.close()
