"""新版启动成功后的健康握手。"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path


def mark_update_healthy(version: str) -> bool:
    """若当前进程由 updater 拉起，原子写入健康标记；普通启动时无操作。"""
    raw = (os.getenv("PDK_UPDATE_HEALTH_FILE") or "").strip()
    nonce = (os.getenv("PDK_UPDATE_HEALTH_NONCE") or "").strip()
    if not raw or not nonce:
        return False
    path = Path(raw)
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        temp = path.with_suffix(path.suffix + ".tmp")
        temp.write_text(json.dumps({"version": version, "nonce": nonce, "timestamp": time.time()}), encoding="utf-8")
        os.replace(temp, path)
        return True
    except OSError as exc:
        print(f"[升级] 写入健康标记失败：{exc}")
        return False
