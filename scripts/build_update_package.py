r"""为 PDK 客户端升级系统生成完整 ZIP 和 update-manifest.json。

示例：
python scripts/build_update_package.py ^
  --source E:\zhibodou\dist\zhibodou ^
  --output E:\zhibodou\dist\updates\zhibodou-1.1.0-windows-x64.zip ^
  --app-id 2 --version 1.1.0 --entry-point zhibodou.exe

清单直接写入 ZIP 根目录，不修改 PyInstaller dist 源目录。
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import zipfile
from pathlib import Path, PurePosixPath


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成 PDK Windows x64 完整升级包")
    parser.add_argument("--source", required=True, help="PyInstaller onedir 输出目录")
    parser.add_argument("--output", required=True, help="目标 ZIP 文件")
    parser.add_argument("--app-id", type=int, required=True)
    parser.add_argument("--version", required=True, help="严格 MAJOR.MINOR.PATCH")
    parser.add_argument("--entry-point", required=True, help="相对 source 的入口 EXE，例如 zhibodou.exe")
    parser.add_argument("--platform", default="WINDOWS")
    parser.add_argument("--arch", default="X64")
    parser.add_argument("--protocol-version", type=int, default=1)
    parser.add_argument("--minimum-updater-version", default="1.0.0")
    parser.add_argument("--allow-config-mismatch", action="store_true", help="仅用于诊断；不建议发布不一致的内嵌配置")
    parser.add_argument("--dry-run", action="store_true", help="只校验并显示统计，不创建 ZIP")
    parser.add_argument("--emit-job", action="store_true",
                        help="同时产出 <包名>.job.json，供 native_updater 的 GUI 直接扫描安装")
    parser.add_argument("--private-key", default=os.environ.get("PDK_UPDATE_ARTIFACT_PRIVATE_KEY"),
                        help="Ed25519 构件私钥（PKCS8 DER 的 Base64）；--emit-job 时必需")
    parser.add_argument("--public-key", default=os.environ.get("PDK_UPDATE_ARTIFACT_PUBLIC_KEY"),
                        help="Ed25519 构件公钥（SPKI DER 的 Base64）；--emit-job 时必需")
    parser.add_argument("--key-id", default=os.environ.get("PDK_UPDATE_ARTIFACT_KEY_ID", "client-release-2026-01"),
                        help="签名 keyId，需与客户端内置公钥的 keyId 一致")
    return parser.parse_args()


def strict_version(value: str) -> bool:
    parts = value.split(".")
    return len(parts) == 3 and all(part.isdigit() and (part == "0" or not part.startswith("0")) for part in parts)


def safe_relative(path: Path, source: Path) -> str:
    relative = path.relative_to(source).as_posix()
    pure = PurePosixPath(relative)
    if pure.is_absolute() or ".." in pure.parts or any(ord(char) < 32 for char in relative):
        raise ValueError(f"不安全的相对路径: {relative}")
    return relative


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def artifact_canonical(app_id: int, version: str, platform: str, arch: str,
                       package_type: str, size: int, sha256: str) -> str:
    """必须与服务端 ClientUpdateService.artifactCanonical 与 C++ crypto.cpp 完全一致。"""
    return "\n".join(["PDK-ARTIFACT-V1", str(app_id), version, platform, arch,
                      package_type, str(size), sha256])


def sign_artifact(private_key_b64: str, canonical: str) -> str:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives.serialization import load_der_private_key

    key = load_der_private_key(base64.b64decode(private_key_b64), password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise ValueError("构件签名密钥必须是 Ed25519 私钥")
    return base64.b64encode(key.sign(canonical.encode("utf-8"))).decode()


def inspect_embedded_config(source: Path, app_id: int, version: str, entry_point: str) -> tuple[list[str], str | None]:
    warnings: list[str] = []
    detected: list[str] = []
    for candidate in source.glob("*.json"):
        if candidate.name == "update-manifest.json":
            continue
        try:
            data = json.loads(candidate.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if "appId" not in data:
            continue
        detected.append(candidate.name)
        if int(data.get("appId", 0)) != app_id:
            warnings.append(f"{candidate.name}: appId={data.get('appId')}，与发布 appId={app_id} 不一致")
        if str(data.get("version", "")) != version:
            warnings.append(f"{candidate.name}: version={data.get('version')}，与发布版本 {version} 不一致")
        if str(data.get("entryPoint", "")) != entry_point:
            warnings.append(f"{candidate.name}: entryPoint={data.get('entryPoint')}，应为 {entry_point}")
    if len(detected) > 1:
        warnings.append("根目录发现多个含 appId 的构建配置: " + ", ".join(detected))
    return warnings, detected[0] if len(detected) == 1 else None


def main() -> int:
    args = parse_args()
    source = Path(args.source).expanduser().resolve()
    output = Path(args.output).expanduser().resolve()
    entry_point = PurePosixPath(args.entry_point.replace("\\", "/")).as_posix()

    if args.app_id <= 0:
        raise ValueError("appId 必须是正整数")
    if not strict_version(args.version) or not strict_version(args.minimum_updater_version):
        raise ValueError("version 和 minimumUpdaterVersion 必须是严格 MAJOR.MINOR.PATCH")
    if not source.is_dir():
        raise FileNotFoundError(f"源目录不存在: {source}")
    if source == output or source in output.parents:
        raise ValueError("输出 ZIP 不能放在 source 目录内部，否则会把自己打进升级包")
    entry_path = source.joinpath(*PurePosixPath(entry_point).parts).resolve()
    if not entry_path.is_file() or source not in entry_path.parents:
        raise FileNotFoundError(f"入口程序不存在或不在 source 内: {entry_point}")

    files: list[tuple[Path, str]] = []
    total_bytes = 0
    for path in source.rglob("*"):
        if path.is_symlink():
            raise ValueError(f"升级包禁止符号链接: {path}")
        if not path.is_file() or path.name == "update-manifest.json":
            continue
        relative = safe_relative(path, source)
        files.append((path, relative))
        total_bytes += path.stat().st_size
    files.sort(key=lambda item: item[1].casefold())

    warnings, build_config = inspect_embedded_config(source, args.app_id, args.version, entry_point)
    manifest = {
        "appId": args.app_id,
        "version": args.version,
        "platform": args.platform.upper(),
        "arch": args.arch.upper(),
        "protocolVersion": args.protocol_version,
        "minimumUpdaterVersion": args.minimum_updater_version,
        "entryPoint": entry_point,
        "buildConfig": build_config,
        "files": [relative for _, relative in files],
    }
    print(f"源目录: {source}")
    print(f"文件数: {len(files)}")
    print(f"未压缩大小: {total_bytes / 1024 / 1024:.2f} MiB")
    print(f"入口程序: {entry_point}")
    for warning in warnings:
        print(f"警告: {warning}", file=sys.stderr)
    if warnings and not args.allow_config_mismatch:
        raise ValueError("内嵌构建配置与升级参数不一致；请先修正并重新执行打包")
    if build_config is None and not args.allow_config_mismatch:
        # 服务端 validateZip 会拒绝 buildConfig 为空的清单，在这里提前失败，避免上传后才发现。
        raise ValueError(
            "未能在源目录找到内嵌构建配置（要求根目录恰好一个含 appId 的 JSON）。"
            "服务端会拒绝 buildConfig 为空的清单；如确需跳过请使用 --allow-config-mismatch。"
        )
    if args.dry_run:
        print("dry-run 完成，未创建 ZIP")
        return 0

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".part")
    temporary.unlink(missing_ok=True)
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as archive:
            archive.writestr("update-manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8"))
            for index, (path, relative) in enumerate(files, start=1):
                archive.write(path, relative)
                if index % 100 == 0 or index == len(files):
                    print(f"已写入 {index}/{len(files)}", end="\r", flush=True)
        os.replace(temporary, output)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise
    print()
    print(f"升级包: {output}")
    zip_sha = sha256_file(output)
    print(f"ZIP 大小: {output.stat().st_size / 1024 / 1024:.2f} MiB")
    print(f"SHA-256: {zip_sha}")

    if args.emit_job:
        # 供 native_updater 的 GUI 扫描安装：canonical 串与服务端签名逻辑完全一致。
        if not args.private_key or not args.public_key:
            raise ValueError("--emit-job 需要 --private-key 与 --public-key（或对应的环境变量）")
        size = output.stat().st_size
        canonical = artifact_canonical(args.app_id, args.version, args.platform.upper(),
                                      args.arch.upper(), "ZIP", size, zip_sha)
        job = {
            "schemaVersion": 1,
            "packagePath": output.name,
            "targetVersion": args.version,
            "entryPoint": entry_point,
            "appId": args.app_id,
            "platform": args.platform.upper(),
            "arch": args.arch.upper(),
            "packageType": "ZIP",
            "fileSize": size,
            "sha256": zip_sha,
            "signature": sign_artifact(args.private_key, canonical),
            "publicKey": args.public_key,
            "signingKeyId": args.key_id,
        }
        job_path = output.with_suffix(".job.json")
        job_path.write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"升级清单: {job_path}")
        print(f"签名 keyId: {args.key_id}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"打包失败: {exc}", file=sys.stderr)
        raise SystemExit(2)
