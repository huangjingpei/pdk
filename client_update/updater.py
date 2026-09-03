"""独立 Windows updater：二次验签、目录级原子切换、健康检查与自动回滚。"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import stat
import subprocess
import sys
import time
import uuid
import zipfile
from datetime import datetime
from pathlib import Path, PurePosixPath
from typing import Any

import requests

from client_update.errors import UpdateError
from client_update.security import artifact_canonical, verify_ed25519


def log(message: str) -> None:
    print(f"[PDK-Updater] {message}", flush=True)


def fail(message: str, code: int = 2) -> None:
    log("失败：" + message)
    raise SystemExit(code)


def sha256_file(path: Path) -> str:
    import hashlib
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def report(event: str, error: str | None = None) -> None:
    decision_path = Path(os.getenv("PDK_UPDATER_DECISION_FILE") or "")
    try:
        decision = json.loads(decision_path.read_text(encoding="utf-8"))
        artifact = decision.get("artifact") or {}
        payload = {
            "checkRequestId": decision["checkRequestId"], "eventToken": decision["eventToken"],
            "artifactId": artifact.get("artifactId"), "eventType": event,
            "fromVersion": decision.get("currentVersion"), "targetVersion": decision.get("targetVersion"),
            "platform": artifact.get("platform") or "WINDOWS", "errorCategory": error,
            "clientTime": datetime.now().isoformat(timespec="seconds"),
        }
        requests.post(
            os.environ["PDK_UPDATER_API_BASE"].rstrip("/") + "/api/v1/client/updates/events",
            headers={"X-PDK-App-ID": str(decision["appId"]),
                     "X-PDK-Device-ID": os.getenv("PDK_UPDATER_DEVICE_ID", "")},
            json=payload, timeout=(3, 5),
        )
        if event == "INSTALL_SUCCEEDED":
            decision_path.unlink(missing_ok=True)
    except Exception:
        pass


def wait_for_parent(pid: int, timeout: int = 90) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            os.kill(pid, 0)
        except OSError:
            return True
        time.sleep(0.5)
    return False


def verify_package(args: argparse.Namespace) -> None:
    package = Path(args.package)
    if not package.is_file() or package.stat().st_size != args.file_size:
        fail("升级包不存在或大小不一致")
    digest = sha256_file(package)
    if digest.lower() != args.sha256.lower():
        fail("升级包 SHA-256 不一致")
    artifact = {
        "platform": args.platform, "arch": args.arch, "packageType": args.package_type,
        "fileSize": args.file_size, "sha256": args.sha256,
    }
    verify_ed25519(artifact_canonical(args.app_id, args.version, artifact),
                   args.signature, args.public_key, "构件")


def _safe_name(name: str) -> PurePosixPath:
    normalized = name.replace("\\", "/")
    pure = PurePosixPath(normalized)
    if not normalized or pure.is_absolute() or ".." in pure.parts or any(ord(c) < 32 for c in normalized):
        fail(f"ZIP 包含不安全路径：{name}")
    return pure


def safe_extract(package: Path, target: Path, args: argparse.Namespace) -> dict[str, Any]:
    with zipfile.ZipFile(package) as archive:
        infos = archive.infolist()
        if len(infos) > 30_000:
            fail("升级包文件数量超过安全限制")
        names = {_safe_name(info.filename).as_posix() for info in infos}
        if "update-manifest.json" not in names:
            fail("升级包缺少 update-manifest.json")
        try:
            manifest = json.loads(archive.read("update-manifest.json"))
        except (KeyError, ValueError) as exc:
            fail(f"升级包清单无法解析：{exc}")
        expected = (args.app_id, args.version, args.platform, args.arch, args.entry_point)
        actual = (int(manifest.get("appId") or 0), str(manifest.get("version") or ""),
                  str(manifest.get("platform") or ""), str(manifest.get("arch") or ""),
                  str(manifest.get("entryPoint") or ""))
        if actual != expected:
            fail(f"升级包清单目标不一致：expected={expected}, actual={actual}")
        build_config = str(manifest.get("buildConfig") or "")
        allowed = {str(v) for v in (manifest.get("files") or [])} | {"update-manifest.json"}
        if not build_config or build_config not in names:
            fail("升级包没有声明有效的 buildConfig")
        if any(name not in allowed and not name.endswith("/") for name in names):
            fail("升级包包含清单之外的文件")
        try:
            embedded = json.loads(archive.read(build_config))
        except (KeyError, ValueError) as exc:
            fail(f"内嵌构建配置无法解析：{exc}")
        embedded_target = (int(embedded.get("appId") or 0), str(embedded.get("version") or ""),
                           str(embedded.get("entryPoint") or ""))
        if embedded_target != (args.app_id, args.version, args.entry_point):
            fail("内嵌构建配置与升级目标不一致")

        total = 0
        for info in infos:
            pure = _safe_name(info.filename)
            mode = info.external_attr >> 16
            if stat.S_IFMT(mode) == stat.S_IFLNK:
                fail("升级包禁止符号链接")
            total += max(0, info.file_size)
            if total > 8 * 1024 ** 3:
                fail("升级包解压体积超过 8 GiB 安全限制")
            destination = target.joinpath(*pure.parts)
            if info.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
                continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, destination.open("wb") as output:
                shutil.copyfileobj(source, output, 1024 * 1024)
        return manifest


def launch_entry(root: Path, entry_point: str, health_file: Path,
                 health_nonce: str) -> subprocess.Popen:
    entry = root.joinpath(*PurePosixPath(entry_point).parts)
    if not entry.is_file():
        fail("新版入口程序不存在")
    env = os.environ.copy()
    env["PDK_UPDATE_HEALTH_FILE"] = str(health_file)
    env["PDK_UPDATE_HEALTH_NONCE"] = health_nonce
    env.pop("_MEIPASS2", None)
    command = [sys.executable, str(entry)] if entry.suffix.lower() == ".py" else [str(entry)]
    return subprocess.Popen(command, cwd=root, env=env,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))


def healthy(path: Path, nonce: str, version: str) -> bool:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data.get("nonce") == nonce and data.get("version") == version
    except (OSError, ValueError):
        return False


def rollback(install_root: Path, backup: Path, failed_root: Path,
             entry_point: str) -> None:
    try:
        if install_root.exists():
            install_root.replace(failed_root)
        backup.replace(install_root)
        old_entry = install_root.joinpath(*PurePosixPath(entry_point).parts)
        if old_entry.is_file():
            subprocess.Popen([str(old_entry)], cwd=install_root,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    finally:
        shutil.rmtree(failed_root, ignore_errors=True)


def install(args: argparse.Namespace) -> int:
    try:
        verify_package(args)
    except UpdateError as exc:
        report("INSTALL_FAILED", "SIGNATURE_INVALID")
        fail(str(exc))
    if not wait_for_parent(args.parent_pid):
        report("INSTALL_FAILED", "MAIN_PROCESS_NOT_EXITED")
        fail("主程序未在 90 秒内退出，安装尚未执行")

    install_root = Path(args.install_root).resolve()
    parent = install_root.parent
    stage = parent / f".{install_root.name}.update-{args.version}-{uuid.uuid4().hex[:8]}.staging"
    backup = parent / f".{install_root.name}.backup-{int(time.time())}"
    failed_root = parent / f".{install_root.name}.failed-{uuid.uuid4().hex[:8]}"
    health_file = Path(args.health_file)
    health_file.unlink(missing_ok=True)
    try:
        stage.mkdir(parents=True)
        safe_extract(Path(args.package), stage, args)
        if not stage.joinpath(*PurePosixPath(args.entry_point).parts).is_file():
            fail("安全解压后找不到新版入口")
        install_root.replace(backup)
        try:
            stage.replace(install_root)
        except Exception:
            backup.replace(install_root)
            raise
        process = launch_entry(install_root, args.entry_point, health_file, args.health_nonce)
        deadline = time.time() + args.health_timeout
        while time.time() < deadline and process.poll() is None:
            if healthy(health_file, args.health_nonce, args.version):
                report("INSTALL_SUCCEEDED")
                shutil.rmtree(backup, ignore_errors=True)
                health_file.unlink(missing_ok=True)
                log(f"升级到 {args.version} 成功")
                return 0
            time.sleep(0.5)
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
        rollback(install_root, backup, failed_root, args.entry_point)
        report("INSTALL_FAILED", "HEALTH_CHECK_FAILED")
        fail("新版未通过启动健康检查，已自动恢复旧版本", 3)
    except SystemExit:
        raise
    except (Exception, UpdateError) as exc:
        report("INSTALL_FAILED", "INSTALL_EXCEPTION")
        if backup.exists() and not install_root.exists():
            backup.replace(install_root)
        fail(str(exc), 4)
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="PDK Windows 桌面客户端独立升级器")
    for name in ("package", "install-root", "version", "entry-point", "platform", "arch",
                 "package-type", "sha256", "signature", "public-key", "health-file", "health-nonce"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--app-id", type=int, required=True)
    parser.add_argument("--file-size", type=int, required=True)
    parser.add_argument("--parent-pid", type=int, required=True)
    parser.add_argument("--health-timeout", type=int, default=45)
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(install(parse_args()))
