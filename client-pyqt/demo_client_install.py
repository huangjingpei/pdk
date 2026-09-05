"""用「真实打的升级包」跑一遍真实的客户端安装事务（离线模式，隔离安全）。

与 test_update_trigger.py 的区别：本脚本复用 scripts/build_update_package.py 已经打好的
真实构件（.workbuddy/demo/ClientApp-1.1.0-windows-x64.zip，用真实 Ed25519 私钥签名），
并把 decision 直接由该包的 job.json 派生，证明「客户端用内置公钥验签 + C++ 升级器接管安装」
对真实构件成立。

不涉及后台发布：decision 由本地 job.json 构造，不走 /check。
切勿把 PDK_INSTALL_ROOT 指向真实客户端安装位置（本脚本指向临时目录）。
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

CLIENT_DIR = Path(__file__).resolve().parent
REPO_ROOT = CLIENT_DIR.parent
NATIVE_UPDATER = REPO_ROOT / "native_updater" / "build" / "Debug" / "pdk_updater.exe"
PACKAGE = REPO_ROOT / ".workbuddy" / "demo" / "ClientApp-1.1.0-windows-x64.zip"
JOB = REPO_ROOT / ".workbuddy" / "demo" / "ClientApp-1.1.0-windows-x64.job.json"
PUBLIC_KEY = "MCowBQYDK2VwAyEAdkohm7YO9wdg+R0VG7wBKaCFcku9oTQuo6iZU3cXQQM="
OLD_VERSION = "1.0.0"
NEW_VERSION = "1.1.0"
APP_ID = 2
ENTRY_POINT = "main.py"
DEVICE_ID = "demo-client-install-0001"

FAKE_CURRENT = '''\
import sys, time
print("CURRENT_CLIENT_RUNNING", flush=True)
time.sleep(1)
'''
CHILD_SRC = '''\
import json, os, sys
from pathlib import Path
CLIENT_DIR = r"{client_dir}"
sys.path.insert(0, CLIENT_DIR)
os.environ["PDK_INSTALL_ROOT"] = r"{install_root}"
os.environ["PDK_UPDATE_CACHE"] = r"{cache_dir}"
os.environ["PDK_UPDATE_ARTIFACT_PUBLIC_KEY"] = r"{public_key}"
from update_client import ClientUpdateManager
native = ClientUpdateManager._find_native_updater()
print("NATIVE_UPDATER " + (str(native) if native else "NONE"), flush=True)
class StubApi:
    base_url = "http://localhost:8080"
    def report_update_event(self, payload, device_id=None):
        print("REPORT " + str(payload.get("eventType")), flush=True); return {{"code": 200}}
decision = json.loads(Path(r"{decision_file}").read_text(encoding="utf-8"))
manager = ClientUpdateManager(StubApi(), {{"version": "{old_version}", "entryPoint": "{entry}", "updaterVersion": "1.0.0"}}, "{device_id}")
manager.launch_updater(decision, Path(r"{package}"))
print("LAUNCHED", flush=True)
os._exit(0)
'''

def run(args):
    p = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.returncode != 0:
        print("STDOUT:", p.stdout); print("STDERR:", p.stderr)
        raise SystemExit(f"命令失败 rc={p.returncode}: {args[1] if len(args) > 1 else args[0]}")
    return p.stdout

def main() -> int:
    assert PACKAGE.is_file(), f"找不到真实构件: {PACKAGE}"
    assert JOB.is_file(), f"找不到 job.json: {JOB}"
    job = json.loads(JOB.read_text(encoding="utf-8"))
    work = Path(tempfile.mkdtemp(prefix="pdk-demo-install-"))
    print(f"工作目录: {work}\n")
    install_root = work / "ClientApp"
    install_root.mkdir(parents=True, exist_ok=True)
    (install_root / ENTRY_POINT).write_text(FAKE_CURRENT, encoding="utf-8")
    (install_root / "app.json").write_text(json.dumps({"appId": APP_ID, "version": OLD_VERSION, "entryPoint": ENTRY_POINT}), encoding="utf-8")
    (install_root / "update-manifest.json").write_text(json.dumps(
        {"appId": APP_ID, "version": OLD_VERSION, "platform": "WINDOWS", "arch": "X64",
         "entryPoint": ENTRY_POINT, "buildConfig": "app.json", "files": [ENTRY_POINT, "app.json"]}, ensure_ascii=False), encoding="utf-8")

    decision = {
        "appId": job["appId"], "targetVersion": job["targetVersion"],
        "checkRequestId": "demo-req-1", "eventToken": "demo-token-1", "hasUpdate": True,
        "updatePolicy": "OPTIONAL",
        "artifact": {
            "artifactId": 9001, "downloadUrl": "http://localhost:8080/demo",
            "fileSize": job["fileSize"], "sha256": job["sha256"], "signature": job["signature"],
            "signingKeyId": job["signingKeyId"], "platform": job["platform"], "arch": job["arch"],
            "packageType": job["packageType"],
        },
    }
    decision_file = work / "decision.json"
    decision_file.write_text(json.dumps(decision, ensure_ascii=False, indent=2), encoding="utf-8")

    cache_dir = work / "cache"; cache_dir.mkdir(parents=True, exist_ok=True)
    child = work / "child_main.py"
    child.write_text(CHILD_SRC.format(client_dir=CLIENT_DIR, install_root=install_root, cache_dir=cache_dir,
                                      public_key=PUBLIC_KEY, decision_file=decision_file, package=PACKAGE,
                                      old_version=OLD_VERSION, entry=ENTRY_POINT, device_id=DEVICE_ID), encoding="utf-8")
    print("=== 客户端主程序交接：拉起 C++ 升级器后退出 ===")
    proc = subprocess.run([sys.executable, str(child)], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
    out = (proc.stdout or "").strip()
    print("  子进程输出:\n    " + out.replace("\n", "\n    "))

    print("\n=== 等待升级器完成事务 ===")
    health_file = cache_dir / "update-health.json"
    echo_file = cache_dir / "health-echo.json"
    installed = OLD_VERSION
    deadline = time.time() + 90
    while time.time() < deadline:
        m = install_root / "update-manifest.json"
        if m.is_file():
            try: installed = json.loads(m.read_text("utf-8")).get("version", OLD_VERSION)
            except json.JSONDecodeError: pass
        if installed == NEW_VERSION: break
        time.sleep(0.5)
    settling = time.time() + 30
    while time.time() < settling:
        if (not health_file.is_file()) and echo_file.is_file(): break
        time.sleep(0.5)

    ok = installed == NEW_VERSION and echo_file.is_file()
    if echo_file.is_file():
        health = json.loads(echo_file.read_text("utf-8"))
        ok = ok and health.get("version") == NEW_VERSION
        print(f"  健康文件: {health}")
    print(f"\n结果: 安装目录版本={installed}  新客户端健康回写={'是' if echo_file.is_file() else '否'}")
    if ok:
        print("PASS ✅ 真实构件 → 客户端内置公钥验签 → C++ 升级器替换 → 新版本启动并回写健康，安装事务打通")
        shutil.rmtree(work, ignore_errors=True)
        return 0
    print("FAIL ❌")
    print(f"现场保留在: {work}")
    return 1

if __name__ == "__main__":
    raise SystemExit(main())
