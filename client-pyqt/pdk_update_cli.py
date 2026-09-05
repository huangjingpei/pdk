#!/usr/bin/env python3
"""PDK 客户端真实升级器（命令行版，非测试）。

复用生产级 client-pyqt/update_client.ClientUpdateManager —— 与 GUI 客户端走**同一条核心代码
路径**（真实后端 check、断点下载、Ed25519 验签、交接 C++ 升级器），只是去掉界面，做成可独立
运行的真实程序。可直接由运维/用户执行，也可被真实客户端在「退出时应用待装」场景下复用。

子命令：
  check     仅检查后端是否有可用更新（不下载）
  download  检查 + 下载 + 验签到本地缓存（不安装）
  update    检查 + 下载 + 验签 + 拉起 C++ 升级器安装到 --install-root（随后本程序退出，升级器接管）
  install   用本地已下载的待装包（pending-update.json）离线重新验签并安装，无需再次联网下载
  status    查看 --install-root 当前装的是什么版本、后端最新版本，以及升级器日志

  install 的缓存自愈：若缓存包与待装元数据不一致（多数是缓存过期/上次下载不完整，而非被篡改），
  默认自动重新下载一次再重试；重新下载后仍会完整验签。用 --no-refresh 可关闭该行为。

安全约定：
  - update / install 必须显式指定 --install-root；禁止指向仓库源码目录或 client-pyqt 自身，
    避免误覆盖开发文件。
  - 验签环节不可跳过：download 用后端返回的 artifact 签名 + 内置公钥；install 重新校验缓存包
    的 SHA-256 与签名，避免「下载一次、长期离线、包被篡改」的风险。

用法：
  python pdk_update_cli.py check    --config config/zhibo-ai.json
  python pdk_update_cli.py download --config config/zhibo-ai.json
  python pdk_update_cli.py update   --config config/zhibo-ai.json --install-root "D:/PDK/ClientApp"
  python pdk_update_cli.py install  --config config/zhibo-ai.json --install-root "D:/PDK/ClientApp"

  # 机读输出（供真实客户端调用解析）
  python pdk_update_cli.py check --config config/zhibo-ai.json --json
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import time
from pathlib import Path

CLIENT_DIR = Path(__file__).resolve().parent
REPO_ROOT = CLIENT_DIR.parent

# 让脚本可直接运行（无需额外设置 PYTHONPATH）
if str(CLIENT_DIR) not in sys.path:
    sys.path.insert(0, str(CLIENT_DIR))

from pdk_client import PdkApiClient  # noqa: E402
from update_client import ClientUpdateManager, UpdateError  # noqa: E402


def _load_config(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise SystemExit(f"构建配置读取失败: {path}: {exc}")
    if int(data.get("appId", 0)) <= 0:
        raise SystemExit(f"构建配置 appId 非法: {path}")
    return data


def _assert_safe_install_root(path: Path) -> Path:
    """拒绝把安装目录指向仓库/客户端源码，避免误覆盖开发文件。"""
    root = path.resolve()
    if root == REPO_ROOT or REPO_ROOT in root.parents:
        raise SystemExit(f"安全拦截：--install-root 不能指向仓库源码目录 ({REPO_ROOT})")
    if root == CLIENT_DIR or CLIENT_DIR in root.parents:
        raise SystemExit(f"安全拦截：--install-root 不能指向客户端源码目录 ({CLIENT_DIR})")
    # 允许全新安装：目录不存在时自动创建。升级器要求目标存在才能做原子替换，
    # 但「首次部署 / 换目录安装」是正常场景，由升级器识别后跳过备份与回滚。
    root.mkdir(parents=True, exist_ok=True)
    return root


def _hand_over(mgr: ClientUpdateManager, decision: dict, pkg: Path, root: Path) -> None:
    """把安装交接给升级器：打印可追踪的日志位置，随后本进程退出。"""
    print(f"→ 拉起升级器，安装到 {root}；本程序退出后由升级器接管替换。")
    mgr.launch_updater(decision, pkg)
    log = mgr.cache_dir / "updater.log"
    print(f"  升级器日志：{log}")
    print(f"  安装结果请查看该日志，或执行 status 子命令确认目录版本。")


class _Progress:
    def __init__(self, quiet: bool = False) -> None:
        self.quiet = quiet
        self._last = -1

    def update(self, done: int, total: int) -> None:
        if self.quiet or total <= 0:
            return
        pct = int(done * 100 / total)
        if pct != self._last:
            self._last = pct
            print(f"\r    下载进度 {done}/{total} ({pct}%)", end="", flush=True)
        if done >= total:
            print()


def _reverify_cached(mgr: ClientUpdateManager, decision: dict, pkg: Path) -> str | None:
    """离线重新校验缓存包（SHA-256 + Ed25519 签名），不依赖网络。

    返回 None 表示通过；否则返回失败原因。注意：不一致多数情况是缓存过期或上次下载
    不完整（例如后台重新发布了同 id 构件），不应直接定性为「被篡改」。
    """
    artifact = decision.get("artifact") or {}
    if not pkg.is_file():
        return f"缓存包不存在：{pkg}"
    hasher = hashlib.sha256()
    with pkg.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(chunk)
    digest = hasher.hexdigest()
    if digest != artifact.get("sha256"):
        return (f"SHA-256 不匹配（待装元数据期望 {artifact.get('sha256')}，"
                f"实际 {digest}）：缓存包已过期或上次下载不完整")
    canonical = "\n".join([
        "PDK-ARTIFACT-V1", str(decision["appId"]), str(decision["targetVersion"]),
        artifact["platform"], artifact["arch"], artifact["packageType"],
        str(artifact["fileSize"]), artifact["sha256"],
    ])
    try:
        mgr._verify(canonical, artifact.get("signature"), artifact.get("signingKeyId"), "artifact")
    except UpdateError as exc:
        return f"Ed25519 验签失败：{exc}"
    return None


def cmd_check(args) -> int:
    cfg = _load_config(Path(args.config))
    client = PdkApiClient(base_url=args.base_url, app_id=int(cfg["appId"]))
    mgr = ClientUpdateManager(client, cfg, args.device_id)
    try:
        decision = mgr.check()
    except UpdateError as exc:
        if args.json:
            print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False))
        else:
            print(f"× 检查失败：{exc}")
        return 2
    has = bool(decision.get("hasUpdate"))
    out = {"ok": True, "hasUpdate": has, "currentVersion": cfg.get("version"),
           "targetVersion": decision.get("targetVersion"), "policy": decision.get("updatePolicy"),
           "sha256": (decision.get("artifact") or {}).get("sha256")}
    if args.json:
        print(json.dumps(out, ensure_ascii=False))
    elif has:
        print(f"✓ 有可用更新：{cfg.get('version')} → {decision.get('targetVersion')}（{decision.get('updatePolicy')}）")
    else:
        print(f"✓ 已是最新（{cfg.get('version')}）")
    return 0


def cmd_download(args) -> int:
    cfg = _load_config(Path(args.config))
    if args.cache_dir:
        os.environ["PDK_UPDATE_CACHE"] = str(Path(args.cache_dir).resolve())
    client = PdkApiClient(base_url=args.base_url, app_id=int(cfg["appId"]))
    mgr = ClientUpdateManager(client, cfg, args.device_id)
    try:
        decision = mgr.check()
    except UpdateError as exc:
        print(f"× 检查失败：{exc}")
        return 2
    if not decision.get("hasUpdate"):
        msg = f"✓ 已是最新（{cfg.get('version')}），无需下载"
        print(msg if not args.json else json.dumps({"ok": True, "downloaded": False, "reason": "UP_TO_DATE"}))
        return 0
    bar = _Progress(args.quiet)
    try:
        pkg = mgr.download_and_verify(decision, bar.update)
    except UpdateError as exc:
        print(f"× 下载/验签失败：{exc}")
        return 3
    if args.json:
        print(json.dumps({"ok": True, "downloaded": True, "package": str(pkg), "size": pkg.stat().st_size,
                          "sha256": decision["artifact"]["sha256"]}, ensure_ascii=False))
    else:
        print(f"✓ 已下载并验签：{pkg}（{pkg.stat().st_size} 字节）")
    return 0


def cmd_update(args) -> int:
    root = _assert_safe_install_root(Path(args.install_root))
    cfg = _load_config(Path(args.config))
    os.environ["PDK_INSTALL_ROOT"] = str(root)
    if args.cache_dir:
        os.environ["PDK_UPDATE_CACHE"] = str(Path(args.cache_dir).resolve())
    if args.native_updater:
        os.environ["PDK_NATIVE_UPDATER"] = str(Path(args.native_updater).resolve())
    client = PdkApiClient(base_url=args.base_url, app_id=int(cfg["appId"]))
    mgr = ClientUpdateManager(client, cfg, args.device_id)
    try:
        decision = mgr.check()
    except UpdateError as exc:
        print(f"× 检查失败：{exc}")
        return 2
    if not decision.get("hasUpdate"):
        print(f"✓ 已是最新（{cfg.get('version')}），无需更新")
        return 0
    bar = _Progress(args.quiet)
    try:
        pkg = mgr.download_and_verify(decision, bar.update)
    except UpdateError as exc:
        print(f"× 下载/验签失败：{exc}")
        return 3
    print(f"✓ 已下载并验签：{pkg}")
    _hand_over(mgr, decision, pkg, root)
    # 模拟主程序在交接后退出：升级器需等父进程退出才能替换文件
    sys.exit(0)


def cmd_install(args) -> int:
    root = _assert_safe_install_root(Path(args.install_root))
    cfg = _load_config(Path(args.config))
    os.environ["PDK_INSTALL_ROOT"] = str(root)
    cache_dir = Path(os.getenv("PDK_UPDATE_CACHE", Path.home() / ".pdk_client" / "updates")).resolve()
    if args.cache_dir:
        cache_dir = Path(args.cache_dir).resolve()
    # 必须让 Manager 与 pending/构件使用同一个缓存目录，否则 job 与日志会写错位置
    os.environ["PDK_UPDATE_CACHE"] = str(cache_dir)
    if args.native_updater:
        os.environ["PDK_NATIVE_UPDATER"] = str(Path(args.native_updater).resolve())
    pending = cache_dir / "pending-update.json"
    if not pending.is_file():
        print(f"× 没有已下载的待装包：{pending}（请先执行 download 或 update）")
        return 4
    decision = json.loads(pending.read_text(encoding="utf-8"))
    artifact = decision.get("artifact") or {}
    pkg = cache_dir / f"artifact-{artifact.get('artifactId')}.zip"
    mgr = ClientUpdateManager(PdkApiClient(base_url=args.base_url, app_id=int(cfg["appId"])), cfg, args.device_id)

    # 缓存包与待装元数据不一致时，默认是「缓存过期/下载不完整」而非包被篡改，
    # 自动重新下载一次；重新下载后依然会完整验签，安全性不降低。
    reason = _reverify_cached(mgr, decision, pkg)
    if reason and not args.no_refresh:
        print(f"! 缓存包校验未通过：{reason}")
        print("  → 自动重新下载后重试…")
        try:
            decision = mgr.check()
            if not decision.get("hasUpdate"):
                print("× 后端当前没有可用更新，无法刷新缓存")
                return 4
            pkg = mgr.download_and_verify(decision, _Progress(args.quiet).update)
        except UpdateError as exc:
            print(f"× 重新下载失败：{exc}")
            return 3
        reason = _reverify_cached(mgr, decision, pkg)
    if reason:
        print(f"× 缓存包校验失败：{reason}")
        print("  提示：若反复出现，请检查后端发布内容与网络链路。")
        return 5

    print(f"✓ 缓存包重新验签通过：{pkg}")
    _hand_over(mgr, decision, pkg, root)
    sys.exit(0)


def cmd_status(args) -> int:
    """查看某个安装目录当前装的是什么版本，以及后端最新可用版本。"""
    root = Path(args.install_root).resolve()
    installed = None
    for name in ("update-manifest.json", "app.json"):
        manifest = root / name
        if manifest.is_file():
            try:
                installed = json.loads(manifest.read_text(encoding="utf-8")).get("version")
                if installed:
                    break
            except ValueError:
                continue
    cfg = _load_config(Path(args.config))
    latest = None
    try:
        client = PdkApiClient(base_url=args.base_url, app_id=int(cfg["appId"]))
        decision = ClientUpdateManager(client, cfg, args.device_id).check()
        latest = decision.get("targetVersion")
        has_update = bool(decision.get("hasUpdate"))
    except UpdateError as exc:
        has_update, latest = None, f"查询失败：{exc}"
    # 判断依据以「安装目录实际版本」为准，而不是构建配置里声明的版本，
    # 否则刚装完最新版仍会显示「有更新」，令人困惑。
    if installed and latest and installed == latest:
        has_update = False
    out = {"installRoot": str(root), "installedVersion": installed,
           "latestVersion": latest, "hasUpdate": has_update}
    if args.json:
        print(json.dumps(out, ensure_ascii=False))
        return 0
    print(f"安装目录：{root}")
    print(f"  已安装版本：{installed or '（未安装）'}")
    print(f"  后端最新：{latest}")
    print(f"  是否有更新：{'是' if has_update else ('否' if has_update is False else '未知')}")
    log = Path(os.getenv("PDK_UPDATE_CACHE", Path.home() / ".pdk_client" / "updates")) / "updater.log"
    if args.cache_dir:
        log = Path(args.cache_dir).resolve() / "updater.log"
    if log.is_file():
        lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
        print(f"  升级器日志（{log}）末尾 {min(10, len(lines))} 行：")
        for line in lines[-10:]:
            print(f"    {line}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    # 公共参数放到独立 parent，使全局选项在子命令前/后都可识别
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--config", default=None, help="客户端构建配置 JSON（appId/version/channel/entryPoint/公钥）")
    common.add_argument("--base-url", default=os.getenv("PDK_API_BASE", "http://localhost:8080"),
                        help="后端地址（默认 http://localhost:8080 或 PDK_API_BASE 环境变量）")
    common.add_argument("--device-id", default="pdk-update-cli", help="设备标识（默认 pdk-update-cli）")
    common.add_argument("--cache-dir", default=None, help="下载缓存目录（默认 ~/.pdk_client/updates）")
    common.add_argument("--json", action="store_true", help="以 JSON 输出机读结果")
    common.add_argument("--quiet", action="store_true", help="静默进度条（脚本场景）")

    p = argparse.ArgumentParser(
        prog="pdk_update_cli", parents=[common],
        description="PDK 客户端真实升级器（命令行版，复用生产级更新代码，非测试）")
    sub = p.add_subparsers(dest="command", required=True)
    # 注意：全局参数（--config/--base-url/...）须写在子命令之前（argparse 约定）。
    sub.add_parser("check", help="仅检查可用更新")
    sub.add_parser("download", help="检查 + 下载 + 验签到缓存")

    up = sub.add_parser("update", help="检查 + 下载 + 验签 + 安装")
    up.add_argument("--install-root", required=True, help="安装目录（必填，禁止指向源码目录）")
    up.add_argument("--native-updater", default=None, help="指定 pdk_updater.exe 路径")

    ins = sub.add_parser("install", help="用本地待装包离线重新验签并安装")
    ins.add_argument("--install-root", required=True, help="安装目录（必填，禁止指向源码目录）")
    ins.add_argument("--native-updater", default=None, help="指定 pdk_updater.exe 路径")
    ins.add_argument("--no-refresh", action="store_true",
                     help="缓存校验失败时不自动重新下载（默认会自动刷新一次再重试）")

    st = sub.add_parser("status", help="查看安装目录当前版本、后端最新版本与升级器日志")
    st.add_argument("--install-root", required=True, help="要查询的安装目录")
    return p


def main() -> int:
    args = build_parser().parse_args()
    if not args.config:
        raise SystemExit("错误：--config 必填（客户端构建配置 JSON）")
    if args.command == "check":
        return cmd_check(args)
    if args.command == "download":
        return cmd_download(args)
    if args.command == "update":
        return cmd_update(args)
    if args.command == "install":
        return cmd_install(args)
    if args.command == "status":
        return cmd_status(args)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
