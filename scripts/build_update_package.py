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
    print(f"ZIP 大小: {output.stat().st_size / 1024 / 1024:.2f} MiB")
    print(f"SHA-256: {sha256_file(output)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"打包失败: {exc}", file=sys.stderr)
        raise SystemExit(2)
