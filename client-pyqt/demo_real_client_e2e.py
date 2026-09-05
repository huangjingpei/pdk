"""真实在线客户端升级端到端验证（走真实后端，非本地包）。

与 demo_client_install.py 的区别：本脚本不提供本地 ZIP，而是让客户端
真正向 http://localhost:8080 发起 check → 下载真实构件 → Ed25519 验签
→ 拉起 C++ 升级器完成安装 → 新版本启动并回写健康。

这等于把「真实客户端主程序」换成一个最小子进程：
    - check()        真实命中后端已发布版本（appId=2 当前 1.0.0 → 1.1.0）
    - download_and_verify()  真实从 downloadUrl 拉取 ZIP 并验签
    - launch_updater()       真实交接给 pdk_updater.exe（与 GUI 客户端同一条代码）
    - 子进程 os._exit(0)     模拟主程序在交接后退出，升级器才动文件

子进程通过环境变量接收所有路径，避免字符串格式化与代码里 dict 字面量冲突。

用法：
    python demo_real_client_e2e.py            # 完整真实在线链路
    python demo_real_client_e2e.py --keep     # 失败时保留现场目录
    python demo_real_client_e2e.py --base-url http://127.0.0.1:8080

注意：本测试会真实替换 PDK_INSTALL_ROOT 指向的目录（测试中指向临时目录），
切勿把 PDK_INSTALL_ROOT 指向真实客户端安装位置。
"""
from __future__ import annotations

import argparse
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
CONFIG = CLIENT_DIR / "config" / "zhibo-ai.json"
DEVICE_ID = "demo-real-client-001"

CHECKS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    CHECKS.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""))
    return ok


# 子进程源码：模拟真实客户端主程序，走「真实后端」全链路后退出。
# 所有路径从环境变量读取，因此本字符串不含任何需要格式化的占位符。
CHILD_SRC = '''\
import json
import os
import sys
from pathlib import Path

CLIENT_DIR = os.environ["PDK_DEMO_CLIENT_DIR"]
sys.path.insert(0, CLIENT_DIR)
os.environ["PDK_INSTALL_ROOT"] = os.environ["PDK_DEMO_INSTALL_ROOT"]
os.environ["PDK_UPDATE_CACHE"] = os.environ["PDK_DEMO_CACHE_DIR"]
os.environ["PDK_DEVICE_ID"] = os.environ["PDK_DEMO_DEVICE_ID"]

from pdk_client import PdkApiClient
from update_client import ClientUpdateManager, UpdateError

BASE = os.environ["PDK_DEMO_BASE_URL"]
DEVICE = os.environ["PDK_DEMO_DEVICE_ID"]
cfg = json.loads(Path(os.environ["PDK_DEMO_CONFIG"]).read_text(encoding="utf-8"))
print("CLIENT_CFG appId=%s version=%s entryPoint=%s" % (cfg.get("appId"), cfg.get("version"), cfg.get("entryPoint")), flush=True)

client = PdkApiClient(base_url=BASE, app_id=int(cfg["appId"]))
mgr = ClientUpdateManager(client, cfg, DEVICE)

print("==> [1] check() 真实命中后端", flush=True)
try:
    decision = mgr.check()
except UpdateError as exc:
    print("CHECK_FAILED " + str(exc), flush=True)
    os._exit(4)
print("    hasUpdate=%s policy=%s target=%s sha256=%s" % (
    decision.get("hasUpdate"), decision.get("updatePolicy"),
    decision.get("targetVersion"), (decision.get("artifact") or {}).get("sha256", "")[:16]), flush=True)
if not decision.get("hasUpdate"):
    print("NO_UPDATE_AVAILABLE", flush=True)
    os._exit(3)

print("==> [2] download_and_verify() 真实下载构件 + Ed25519 验签", flush=True)
def prog(done, total):
    print("    PROGRESS %d/%d" % (done, total), flush=True)
try:
    package = mgr.download_and_verify(decision, prog)
except UpdateError as exc:
    print("DOWNLOAD_VERIFY_FAILED " + str(exc), flush=True)
    os._exit(5)
print("    DOWNLOADED %d bytes (expect %d)" % (
    package.stat().st_size, int((decision.get("artifact") or {}).get("fileSize", 0))), flush=True)

print("==> [3] launch_updater() 交接给 C++ 升级器", flush=True)
mgr.launch_updater(decision, package)
print("LAUNCHED", flush=True)
os._exit(0)
'''


def make_old_client(install_root: Path) -> None:
    """造一个「当前版本 1.0.0」的老客户端安装目录（会被升级器整体替换）。"""
    install_root.mkdir(parents=True, exist_ok=True)
    (install_root / "main.py").write_text(
        'import time\nprint("OLD_CLIENT_1.0.0_RUNNING", flush=True)\ntime.sleep(1)\n',
        encoding="utf-8")
    (install_root / "app.json").write_text(json.dumps(
        {"appId": 2, "version": "1.0.0", "entryPoint": "main.py"}, ensure_ascii=False), encoding="utf-8")
    (install_root / "update-manifest.json").write_text(json.dumps(
        {"appId": 2, "version": "1.0.0", "platform": "WINDOWS", "arch": "X64",
         "entryPoint": "main.py", "files": ["main.py", "app.json"]}, ensure_ascii=False), encoding="utf-8")


def installed_version(install_root: Path) -> str:
    for name in ("update-manifest.json", "app.json"):
        p = install_root / name
        if p.is_file():
            try:
                return json.loads(p.read_text("utf-8")).get("version", "")
            except json.JSONDecodeError:
                pass
    return ""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="失败时保留现场目录")
    parser.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args()

    native = REPO_ROOT / "native_updater" / "build" / "Debug" / "pdk_updater.exe"
    if not native.is_file():
        print("⚠️ C++ 升级器不存在，请先构建：cmake --build native_updater/build --config Debug")
        return 1

    work = Path(tempfile.mkdtemp(prefix="pdk-real-e2e-"))
    print(f"工作目录: {work}\n")
    install_root = work / "ClientApp"
    cache_dir = work / "cache"

    print("=== STEP 0 环境自检 ===")
    check("C++ 原生升级器存在", native.is_file(), str(native))
    check("构建配置存在", CONFIG.is_file(), str(CONFIG))

    print("\n=== STEP 1 构造真实老客户端（当前版本 1.0.0）===")
    make_old_client(install_root)
    check("老客户端安装目录就绪（v1.0.0）", installed_version(install_root) == "1.0.0")

    print("\n=== STEP 2 启动客户端子进程：真实 check → 下载 → 验签 → 交接升级器 ===")
    child_file = work / "child_client.py"
    child_file.write_text(CHILD_SRC, encoding="utf-8")
    env = os.environ.copy()
    env.update({
        "PDK_DEMO_CLIENT_DIR": str(CLIENT_DIR),
        "PDK_DEMO_INSTALL_ROOT": str(install_root),
        "PDK_DEMO_CACHE_DIR": str(cache_dir),
        "PDK_DEMO_DEVICE_ID": DEVICE_ID,
        "PDK_DEMO_BASE_URL": args.base_url,
        "PDK_DEMO_CONFIG": str(CONFIG),
    })
    proc = subprocess.run([sys.executable, str(child_file)], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=180, env=env)
    print("  子进程输出:")
    for line in (proc.stdout or "").splitlines():
        print("    " + line)
    if proc.stderr.strip():
        print("  子进程 STDERR:")
        for line in proc.stderr.splitlines():
            print("    " + line)

    check("子进程正常退出（交接后主程序退出）", proc.returncode == 0, f"rc={proc.returncode}")
    check("check 命中真实后端更新", "hasUpdate=True" in proc.stdout)
    check("下载并验签通过（无 DOWNLOAD_VERIFY_FAILED）",
          "DOWNLOAD_VERIFY_FAILED" not in proc.stdout and "DOWNLOADED" in proc.stdout)
    check("已交接 C++ 升级器", "LAUNCHED" in proc.stdout)

    job_path = cache_dir / "update-job.json"
    job = json.loads(job_path.read_text("utf-8")) if job_path.is_file() else {}
    check("客户端生成了 update-job.json 供升级器执行", job_path.is_file())

    print("\n=== STEP 3 等待升级器完成真实安装事务 ===")
    new_version = ""
    deadline = time.time() + 90
    while time.time() < deadline:
        new_version = installed_version(install_root)
        if new_version == "1.1.0":
            break
        time.sleep(0.5)
    check("安装目录已切换到 1.1.0（真实构件已落地）", new_version == "1.1.0", f"实际={new_version}")

    health_echo = cache_dir / "health-echo.json"
    health_file = cache_dir / "update-health.json"
    settling = time.time() + 30
    while time.time() < settling:
        if health_echo.is_file():
            break
        time.sleep(0.5)
    check("新版本 1.1.0 客户端真实启动并回写健康文件", health_echo.is_file())
    if health_echo.is_file():
        health = json.loads(health_echo.read_text("utf-8"))
        check("健康文件版本为 1.1.0", health.get("version") == "1.1.0", f"实际={health.get('version')}")
        check("健康 nonce 与 job 一致（防回滚误判）", health.get("nonce") == job.get("healthNonce"))
    else:
        check("健康文件版本为 1.1.0", False)
        check("健康 nonce 与 job 一致", False)

    def leftovers() -> list[str]:
        return [p.name for p in work.iterdir() if p.is_dir() and (".backup-" in p.name or ".failed-" in p.name)]

    settle2 = time.time() + 20
    while time.time() < settle2:
        if not leftovers() and not health_file.is_file():
            break
        time.sleep(0.5)
    check("无残留备份目录（健康检查通过才会清理）", not leftovers(), ",".join(leftovers()) or "已清理")
    check("升级器已回收临时健康文件", not health_file.is_file())

    print("\n=== 结果 ===")
    failed = [n for n, ok, _ in CHECKS if not ok]
    passed = len(CHECKS) - len(failed)
    print(f"{passed}/{len(CHECKS)} 项通过")
    if failed:
        print("失败项: " + ", ".join(failed))
        if args.keep:
            print(f"现场保留在: {work}")
        return 1
    shutil.rmtree(work, ignore_errors=True)
    print("PASS ✅ 真实在线链路打通：客户端 check → 后端真实下载构件 → Ed25519 验签 → C++ 升级器安装 → 1.1.0 启动回写健康")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
