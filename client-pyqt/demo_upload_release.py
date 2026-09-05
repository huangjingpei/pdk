"""真实跑通「上传后台」：登录管理员 → 建版本 → 上传构件 → 后端重签 → 置 READY。

安全约定：
- 默认【不发布】(不调 publish)，真实客户端（appId 2 / zhibo-ai）不会被波及。
- 仅清理「本脚本创建的 demo 版本」；若 1.1.0 已存在且已发布，直接中止，绝不触碰。
- 密码从环境变量读取，不在日志中打印。

用法：
  set PDK_ADMIN_USER=你的账号
  set PDK_ADMIN_PASS=你的密码
  python demo_upload_release.py                 # 建到 READY 即止（安全）
  python demo_upload_release.py --publish        # 额外发布（会让 appId 2 客户端看到，慎用）
  python demo_upload_release.py --cleanup       # 演示完删除该草稿，不留痕迹
"""
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

import requests

REPO_ROOT = Path(__file__).resolve().parent.parent
PACKAGE = REPO_ROOT / ".workbuddy" / "demo" / "ClientApp-1.1.0-windows-x64.zip"
API = os.getenv("PDK_API_BASE", "http://localhost:8080").rstrip("/")
APP_ID = 2
VERSION = "1.1.0"
CHANNEL = "STABLE"
PLATFORM = "WINDOWS"
ARCH = "X64"
PKG_TYPE = "ZIP"


def auth_headers(token: dict) -> dict:
    return {token["tokenName"]: token["tokenValue"]}


def login() -> dict:
    user = os.getenv("PDK_ADMIN_USER")
    pwd = os.getenv("PDK_ADMIN_PASS")
    if not user or not pwd:
        raise SystemExit("缺少环境变量 PDK_ADMIN_USER / PDK_ADMIN_PASS，请先 export 后再运行。")
    r = requests.post(f"{API}/api/v1/admin/auth/login",
                      json={"username": user, "password": pwd}, timeout=15)
    if r.status_code != 200 or r.json().get("code") != 200:
        raise SystemExit(f"管理员登录失败 {r.status_code}: {r.text[:300]}")
    data = r.json()["data"]
    print(f"[登录] ✅ {data['username']} 角色={data['role']} 权限={data['permissions']}")
    return {"tokenName": data["tokenName"], "tokenValue": data["tokenValue"]}


def find_demo_release(h: dict) -> dict | None:
    page = 1
    while True:
        r = requests.get(f"{API}/api/v1/admin/client-updates/releases",
                         headers=h, params={"bizId": APP_ID, "channel": CHANNEL, "page": page, "size": 50}, timeout=15)
        if r.status_code != 200:
            raise SystemExit(f"查询版本失败 {r.status_code}: {r.text[:300]}")
        rows = r.json()["data"]["records"]
        for row in rows:
            if row["version"] == VERSION:
                return row
        if not rows or page >= r.json()["data"]["pages"]:
            break
        page += 1
    return None


def ensure_clean_slate(h: dict) -> None:
    row = find_demo_release(h)
    if row is None:
        return
    st = row["status"]
    rid = row["id"]
    if st in ("PUBLISHED", "SUSPENDED", "ARCHIVED"):
        raise SystemExit(f"已存在 {VERSION} 且状态={st}（疑似真实发布），脚本拒绝触碰，请人工处理。")
    print(f"[清理] 发现旧 demo 草稿 {rid}({st})，先删除避免重复")
    if st == "READY":
        req(h, "POST", f"/releases/{rid}/draft", {"requestId": rid_tag("draft"), "reason": "demo cleanup"})
    req(h, "DELETE", f"/releases/{rid}", {"requestId": rid_tag("del"), "reason": "demo cleanup"})


def rid_tag(what: str) -> str:
    return f"DEMO-{what}-{int(time.time()*1000)}"


def req(h: dict, method: str, path: str, body: dict | None = None, files=None, expected=200) -> dict:
    url = f"{API}{path}"
    if files is not None:
        r = requests.request(method, url, headers=h, files=files, timeout=30)
    else:
        r = requests.request(method, url, headers=h, json=body, timeout=30)
    # 本机后端约定：业务错误也返回 HTTP 200，错误码在 body.code，必须二次校验。
    try:
        payload = r.json()
    except ValueError:
        raise SystemExit(f"{method} {path} -> 非 JSON 响应 {r.status_code}: {r.text[:400]}")
    if payload.get("code") != 200:
        raise SystemExit(f"{method} {path} -> 业务码 {payload.get('code')}: {payload.get('message')} | {r.text[:400]}")
    if r.status_code != expected:
        raise SystemExit(f"{method} {path} -> HTTP {r.status_code}: {r.text[:400]}")
    data = payload.get("data")
    return data if isinstance(data, dict) else {"raw": payload}


def main() -> int:
    publish = "--publish" in sys.argv
    cleanup = "--cleanup" in sys.argv
    token = login()
    h = auth_headers(token)

    ensure_clean_slate(h)

    print("\n=== STEP 1 创建版本草稿 ===")
    rel = req(h, "POST", "/api/v1/admin/client-updates/releases", {
        "appId": APP_ID, "version": VERSION, "channel": CHANNEL,
        "minimumProtocolVersion": 1, "minimumUpdaterVersion": "1.0.0",
        "releaseNotes": "DEMO 自动演示版本：升级链路端到端验证", "rolloutPercentage": 100,
        "requestId": rid_tag("create"),
    })
    print(f"  版本草稿 id={rel['id']} status={rel['status']}")

    print("\n=== STEP 2 创建构件上传会话 ===")
    art = req(h, "POST", f"/api/v1/admin/client-updates/releases/{rel['id']}/artifacts/upload-session", {
        "platform": PLATFORM, "arch": ARCH, "packageType": PKG_TYPE,
        "fileName": PACKAGE.name, "requestId": rid_tag("upload-session"),
    })
    print(f"  构件 id={art['id']} status={art['status']}")

    print("\n=== STEP 3 上传构件 ZIP（后端校验结构 + 计算 SHA-256）===")
    with PACKAGE.open("rb") as fh:
        up = req(h, "PUT", f"/api/v1/admin/client-updates/artifacts/{art['id']}/content",
                 files={"file": (PACKAGE.name, fh, "application/zip")})
    print(f"  已存储 status={up['status']} sha256={up['sha256'][:24]}… size={up['fileSize']}")

    print("\n=== STEP 4 complete：后端用私钥对构件重新签名 ===")
    done = req(h, "POST", f"/api/v1/admin/client-updates/artifacts/{art['id']}/complete", {
        "requestId": rid_tag("complete"), "reason": "DEMO 完成构件签名",
    })
    print(f"  构件签名算法={done.get('signatureAlgorithm')} keyId={done.get('signingKeyId')} status={done.get('status')}")

    print("\n=== STEP 5 置为 READY（可发布状态，尚未发布）===")
    ready = req(h, "POST", f"/api/v1/admin/client-updates/releases/{rel['id']}/ready", {
        "requestId": rid_tag("ready"), "reason": "DEMO 置为就绪",
    })
    print(f"  状态={ready['status']}")

    print("\n=== 结果：上传后台链路已全部跑通（版本=%s 构件=%s 已后端签名）===" % (VERSION, art['id']))
    print(f"  后台草稿 id={rel['id']}，当前状态={ready['status']}（未发布，真实客户端无感）")

    if publish:
        pub = req(h, "POST", f"/api/v1/admin/client-updates/releases/{rel['id']}/publish", {
            "requestId": rid_tag("publish"), "reason": "DEMO 发布",
        })
        print(f"[发布] ⚠️ 已发布 status={pub['status']} —— appId {APP_ID} 的客户端现在会看到 {VERSION}")
    else:
        print("  未加 --publish：保持 READY，真实客户端不会被推送。")

    if cleanup:
        print("\n=== STEP 6 清理 demo 草稿 ===")
        if ready["status"] == "READY":
            req(h, "POST", f"/releases/{rel['id']}/draft", {"requestId": rid_tag("draft2"), "reason": "demo cleanup"})
        req(h, "DELETE", f"/releases/{rel['id']}", {"requestId": rid_tag("del2"), "reason": "demo cleanup"})
        print("  已删除 demo 草稿，后台无残留。")
    else:
        print(f"\n如需清理：python demo_upload_release.py --cleanup")
        if not publish:
            print(f"如需真正推送给客户端：python demo_upload_release.py --publish")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
