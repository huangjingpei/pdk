"""客户端触发升级的端到端验证。

真实走一遍 client-pyqt 的触发链路（跳过后端发布数据，直接验证「客户端如何把升级
交接给升级器」这一段）：

    构造假客户端 → 打包签名新版本 → ClientUpdateManager.launch_updater()
    → 主程序退出 → C++ pdk_updater.exe 接管 → 替换 → 启动 → 健康检查

用法：
    python test_update_trigger.py            # 完整端到端
    python test_update_trigger.py --keep     # 失败时保留现场目录

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
NATIVE_UPDATER = REPO_ROOT / "native_updater" / "build" / "Debug" / "pdk_updater.exe"
PACK_SCRIPT = REPO_ROOT / "scripts" / "build_update_package.py"
KEYS_SCRIPT = REPO_ROOT / "scripts" / "generate_update_keys.py"

OLD_VERSION = "1.0.0"
NEW_VERSION = "2.0.0"
APP_ID = 3
ENTRY_POINT = "fake_client.py"
DEVICE_ID = "trigger-test-device-0001"

# 新版本客户端：模拟 main.py 修复后的健康检查契约——收到 nonce 就写
# {"nonce":..., "version":...}，否则升级器会判定启动失败并回滚。
FAKE_CLIENT_SRC = '''\
import json
import os
import time
from pathlib import Path

VERSION = "{version}"

health_file = os.getenv("PDK_UPDATE_HEALTH_FILE", "")
nonce = os.getenv("PDK_UPDATE_HEALTH_NONCE", "")
if health_file and nonce:
    payload = json.dumps({{"nonce": nonce, "version": VERSION}}, ensure_ascii=False)
    Path(health_file).write_text(payload, encoding="utf-8")
    # 升级器在健康检查通过后会删除健康文件，另存一份副本供测试断言。
    Path(health_file).with_name("health-echo.json").write_text(payload, encoding="utf-8")
    print("HEALTH_WRITTEN " + VERSION, flush=True)

time.sleep(2)
'''

# 子进程：模拟客户端主程序——调用 launch_updater 交接后立即退出。
CHILD_SRC = '''\
import json
import os
import sys
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
    """只记录上报事件；不依赖后端密钥配置。"""
    base_url = "http://localhost:8080"

    def report_update_event(self, payload, device_id=None):
        print("REPORT " + str(payload.get("eventType")), flush=True)
        return {{"code": 200}}


decision = json.loads(Path(r"{decision_file}").read_text(encoding="utf-8"))
manager = ClientUpdateManager(
    StubApi(),
    {{"version": "{old_version}", "entryPoint": "{entry}", "updaterVersion": "1.0.0"}},
    "{device_id}",
)
manager.launch_updater(decision, Path(r"{package}"))
print("LAUNCHED", flush=True)
os._exit(0)
'''

CHECKS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    CHECKS.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""))
    return ok


def run(args: list[str]) -> str:
    proc = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        print("STDOUT:", proc.stdout)
        print("STDERR:", proc.stderr)
        raise SystemExit(f"命令失败 rc={proc.returncode}: {args[1] if len(args) > 1 else args[0]}")
    return proc.stdout


def make_tree(root: Path, version: str) -> None:
    """造一个假客户端安装目录。"""
    root.mkdir(parents=True, exist_ok=True)
    (root / ENTRY_POINT).write_text(FAKE_CLIENT_SRC.format(version=version), encoding="utf-8")
    (root / "app.json").write_text(json.dumps(
        {"appId": APP_ID, "version": version, "entryPoint": ENTRY_POINT}, ensure_ascii=False),
        encoding="utf-8")
    if version == OLD_VERSION:  # 新版本的 manifest 由打包脚本生成
        (root / "update-manifest.json").write_text(json.dumps(
            {"appId": APP_ID, "version": version, "platform": "WINDOWS", "arch": "X64",
             "entryPoint": ENTRY_POINT, "buildConfig": "app.json",
             "files": [ENTRY_POINT, "app.json"]}, ensure_ascii=False), encoding="utf-8")


def diagnose_online(config_path: Path) -> None:
    """用真实后端数据 + 真实客户端构建配置，逐步诊断触发链路卡在哪一步。

    不作为断言：这是环境诊断，输出 ✅/⚠️ 供人工判断。
    """
    import requests

    sys.path.insert(0, str(CLIENT_DIR))
    os.environ["PDK_CLIENT_CONFIG"] = str(config_path)
    from pdk_client import load_client_config
    from update_client import ClientUpdateManager, UpdateError

    cfg = load_client_config()
    app_id = int(cfg["appId"])
    base_url = os.getenv("PDK_API_BASE", "http://localhost:8080")
    print(f"构建配置: {config_path}")
    print(f"  appId={app_id}  version={cfg.get('version')}  entryPoint={cfg.get('entryPoint')}")
    for field in ("artifactPublicKeys", "policyPublicKeys"):
        keys = cfg.get(field) or {}
        mark = "✅" if keys else "⚠️ 空"
        print(f"  {field}: {mark} {list(keys)}")

    print(f"\n[1] 调用真实 check 接口 {base_url}")
    try:
        resp = requests.get(
            f"{base_url}/api/v1/client/updates/check",
            headers={"X-PDK-App-ID": str(app_id), "X-PDK-Client-Version": str(cfg.get("version", "1.0.0")),
                     "X-PDK-Platform": "WINDOWS", "X-PDK-Arch": "X64", "X-PDK-Device-ID": DEVICE_ID},
            params={"currentVersion": str(cfg.get("version", "1.0.0")), "platform": "WINDOWS", "arch": "X64",
                    "channel": str(cfg.get("channel", "STABLE")), "protocolVersion": 1,
                    "updaterVersion": str(cfg.get("updaterVersion", "1.0.0"))},
            timeout=10)
        data = resp.json().get("data") or {}
    except Exception as exc:
        print(f"  ⚠️ 无法连接后端: {exc}")
        return
    print(f"  ✅ hasUpdate={data.get('hasUpdate')} policy={data.get('updatePolicy')} "
          f"target={data.get('targetVersion')} reason={data.get('reason')}")

    if not data.get("hasUpdate"):
        print("  → 后端无可用更新，链路到此为止（属正常，非故障）")
        return

    print("\n[2] 用客户端内置公钥验签策略与构件")
    api_base = base_url  # 类体内若用同名赋值会被自身遮蔽，故改名

    class StubApi:
        client_version = str(cfg.get("version", "1.0.0"))
        base_url = api_base

        def check_update(self, *a, **k):
            return {"code": 200, "data": data}

        def report_update_event(self, *a, **k):
            return {"code": 200}

    manager = ClientUpdateManager(StubApi(), cfg, DEVICE_ID)
    try:
        manager.check()
        print("  ✅ 策略验签通过")
    except UpdateError as exc:
        print(f"  ⚠️ 验签失败: {exc}")
        print("  → 修复：把 generate_update_keys.py 产出的公钥填入构建配置的")
        print("    artifactPublicKeys / policyPublicKeys（keyId 要与后端一致），")
        print("    或设置环境变量 PDK_UPDATE_ARTIFACT_PUBLIC_KEY / PDK_UPDATE_POLICY_PUBLIC_KEY。")
        return

    artifact = data.get("artifact") or {}
    print(f"  ✅ 构件就绪 artifactId={artifact.get('artifactId')} "
          f"size={artifact.get('fileSize')} keyId={artifact.get('signingKeyId')}")
    print("\n[3] 结论：check → 验签均通过，客户端可正常触发升级。")
    print("    完整安装需下载构件，本诊断不执行；要跑完整事务请用默认（离线）模式。")


def make_child(work: Path, name: str, install_root: Path, cache_dir: Path,
               public_key: str, decision_file: Path, package: Path) -> Path:
    """生成模拟客户端主程序的子进程脚本：调起升级器后立刻退出。"""
    path = work / name
    path.write_text(CHILD_SRC.format(
        client_dir=CLIENT_DIR, install_root=install_root, cache_dir=cache_dir,
        public_key=public_key, decision_file=decision_file, package=package,
        old_version=OLD_VERSION, entry=ENTRY_POINT, device_id=DEVICE_ID), encoding="utf-8")
    return path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="失败时保留现场目录")
    parser.add_argument("--online", metavar="CONFIG", nargs="?", const="config/zhibo-ai.json",
                        help="用真实后端与指定客户端构建配置做链路诊断（默认 config/zhibo-ai.json）")
    args = parser.parse_args()

    if args.online:
        diagnose_online(Path(args.online).resolve())
        return 0

    work = Path(tempfile.mkdtemp(prefix="pdk-trigger-test-"))
    print(f"工作目录: {work}\n")

    print("=== STEP 0 环境自检 ===")
    check("C++ 原生升级器存在", NATIVE_UPDATER.is_file(), str(NATIVE_UPDATER))
    check("打包脚本存在", PACK_SCRIPT.is_file())
    if not NATIVE_UPDATER.is_file():
        print("\n请先构建：cmake --build native_updater/build --config Debug")
        return 1

    print("\n=== STEP 1 构造假客户端（当前版本 " + OLD_VERSION + "）===")
    install_root = work / "ClientApp"
    source = work / f"src-{NEW_VERSION}"
    make_tree(install_root, OLD_VERSION)
    make_tree(source, NEW_VERSION)
    check("旧版本安装目录就绪",
          json.loads((install_root / "update-manifest.json").read_text("utf-8"))["version"] == OLD_VERSION)

    print(f"\n=== STEP 2 生成 Ed25519 密钥并打包 {NEW_VERSION} ===")
    keys = dict(line.split("=", 1) for line in run([sys.executable, str(KEYS_SCRIPT)]).splitlines()
                if line.startswith("PDK_") and "=" in line)
    private_key, public_key = keys["PDK_UPDATE_ARTIFACT_PRIVATE_KEY"], keys["PDK_UPDATE_ARTIFACT_PUBLIC_KEY"]
    print("  密钥生成 OK")

    package = work / f"ClientApp-{NEW_VERSION}-windows-x64.zip"
    run([sys.executable, str(PACK_SCRIPT), "--source", str(source), "--output", str(package),
         "--app-id", str(APP_ID), "--version", NEW_VERSION, "--entry-point", ENTRY_POINT,
         "--emit-job", "--private-key", private_key, "--public-key", public_key])
    job_file = package.parent / (package.stem + ".job.json")
    check("升级包已产出", package.is_file(), f"{package.stat().st_size} 字节")
    check("签名清单 job.json 已产出", job_file.is_file())

    emitted = json.loads(job_file.read_text(encoding="utf-8"))

    print("\n=== STEP 3 构造后端 check 响应（decision）===")
    decision = {
        "appId": emitted["appId"],
        "targetVersion": emitted["targetVersion"],
        "checkRequestId": "test-check-request-id",
        "eventToken": "test-event-token",
        "hasUpdate": True,
        "updatePolicy": "OPTIONAL",
        "artifact": {
            "artifactId": 9001,
            "downloadUrl": "http://localhost:8080/api/v1/client/updates/download/test",
            "fileSize": emitted["fileSize"],
            "sha256": emitted["sha256"],
            "signature": emitted["signature"],
            "signingKeyId": emitted.get("signingKeyId", "client-release-2026-01"),
            "platform": emitted["platform"],
            "arch": emitted["arch"],
            "packageType": emitted["packageType"],
        },
    }
    decision_file = work / "decision.json"
    decision_file.write_text(json.dumps(decision, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"  目标版本={decision['targetVersion']}  sha256={decision['artifact']['sha256'][:16]}…")

    print("\n=== STEP 4 客户端触发升级（子进程调用后立刻退出，模拟主程序交接）===")
    cache_dir = work / "cache"
    cache_dir.mkdir(parents=True, exist_ok=True)
    child_file = make_child(work, "child_main.py", install_root, cache_dir,
                            public_key, decision_file, package)
    proc = subprocess.run([sys.executable, str(child_file)], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=120)
    child_out = (proc.stdout or "").strip()
    print("  子进程输出:\n    " + child_out.replace("\n", "\n    "))

    check("子进程正常退出", proc.returncode == 0, f"rc={proc.returncode}")
    check("找到 C++ 原生升级器（非 Python 回退）", "NATIVE_UPDATER" in child_out and "NONE" not in child_out)
    check("已上报 INSTALL_STARTED", "REPORT INSTALL_STARTED" in child_out)
    check("主程序已交接退出", "LAUNCHED" in child_out)

    job_path = cache_dir / "update-job.json"
    check("客户端生成了 update-job.json", job_path.is_file())
    job = json.loads(job_path.read_text(encoding="utf-8")) if job_path.is_file() else {}
    check("job 携带 Ed25519 公钥", bool(job.get("publicKey")))
    check("job 指向被测安装目录", job.get("installRoot") == str(install_root.resolve()))

    print("\n=== STEP 5 等待升级器完成事务 ===")
    # 注意：升级器健康检查通过后会删除健康文件并清理备份，因此用客户端另存的副本断言，
    # 而「备份已清理」本身正是健康检查通过的证据。
    # 两段式等待：先等版本切换，再等升级器收尾（清理备份 + 回收健康文件）。
    # 只等版本就断言会撞上竞态——升级器此刻可能还没走到清理步骤。
    health_file = cache_dir / "update-health.json"
    echo_file = cache_dir / "health-echo.json"
    installed = OLD_VERSION
    deadline = time.time() + 90
    while time.time() < deadline:
        manifest = install_root / "update-manifest.json"
        if manifest.is_file():
            try:
                installed = json.loads(manifest.read_text("utf-8")).get("version", OLD_VERSION)
            except json.JSONDecodeError:
                pass
        if installed == NEW_VERSION:
            break
        time.sleep(0.5)

    check(f"安装目录已切换到 {NEW_VERSION}", installed == NEW_VERSION, f"实际={installed}")

    def leftovers() -> list[str]:
        return [p.name for p in work.iterdir() if p.is_dir() and (".failed-" in p.name or ".backup-" in p.name)]

    settling = time.time() + 30
    while time.time() < settling:
        if not leftovers() and not health_file.is_file():
            break
        time.sleep(0.5)
    check("新客户端写回了健康文件", echo_file.is_file())
    if echo_file.is_file():
        health = json.loads(echo_file.read_text("utf-8"))
        check("健康文件 nonce 与 job 一致", health.get("nonce") == job.get("healthNonce"))
        check("健康文件版本为新版本", health.get("version") == NEW_VERSION, f"实际={health.get('version')}")
    else:
        check("健康文件 nonce 与 job 一致", False)
        check("健康文件版本为新版本", False)

    leftovers_now = leftovers()
    check("无失败/残留备份目录（健康检查通过才会清理备份）", not leftovers_now,
          ",".join(leftovers_now) or "已清理")
    check("升级器已回收临时健康文件", not health_file.is_file())

    # 反向用例：篡改摘要，验签必须拒绝，安装目录必须保持原样。
    # 没有这一条，前面的「成功」无法证明验签真的在工作。
    print("\n=== STEP 6 反向验证：篡改摘要必须被拒绝 ===")
    tamper_root = work / "ClientAppTamper"
    tamper_cache = work / "cache-tamper"
    make_tree(tamper_root, OLD_VERSION)
    tamper_cache.mkdir(parents=True, exist_ok=True)
    tampered = json.loads(json.dumps(decision))
    tampered["artifact"]["sha256"] = "0" * 64  # 与真实内容不符
    tamper_file = work / "decision-tampered.json"
    tamper_file.write_text(json.dumps(tampered, ensure_ascii=False, indent=2), encoding="utf-8")
    print("  已将 sha256 篡改为全 0，触发升级…")

    proc2 = subprocess.run([sys.executable, str(make_child(work, "child_tamper.py", tamper_root,
                                                           tamper_cache, public_key, tamper_file, package))],
                           capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
    time.sleep(12)  # 给升级器留出验签并退出的时间
    tamper_version = OLD_VERSION
    manifest2 = tamper_root / "update-manifest.json"
    if manifest2.is_file():
        tamper_version = json.loads(manifest2.read_text("utf-8")).get("version", OLD_VERSION)
    check("篡改后的包未被安装", tamper_version == OLD_VERSION, f"版本仍为 {tamper_version}")
    tamper_backups = [p.name for p in work.iterdir() if p.is_dir() and ".backup-" in p.name]
    check("篡改场景未产生备份（在替换前就被拒绝）", not tamper_backups, ",".join(tamper_backups) or "无备份")

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
    print("PASS ✅ 客户端触发 → C++ 升级器接管 → 新版本启动并回报健康，全链路打通")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
