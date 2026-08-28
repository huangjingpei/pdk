"""ZHIBO_LIVE 登录、申请短效票据并调用 FFmpeg 推流的最小演示。"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys

from pdk_client import PdkApiClient, default_device_id


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="PDK ZHIBO_LIVE MediaMTX 推流演示")
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--phone", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--device-id", default=default_device_id())
    parser.add_argument("--input", help="本地视频文件；不填时生成测试画面和静音音频")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    ffmpeg = shutil.which(args.ffmpeg)
    if not ffmpeg:
        print("未找到 FFmpeg，请安装后把 ffmpeg 加入 PATH，或通过 --ffmpeg 指定路径。", file=sys.stderr)
        return 2

    client = PdkApiClient(args.api, app_id=3)
    login = client.login(args.phone, args.password, args.device_id)
    if not client.is_ok(login):
        print(f"登录失败: {login.get('code')} {login.get('message')}", file=sys.stderr)
        return 3
    ticket = client.create_live_publish_ticket(title="FFmpeg联调")
    if not client.is_ok(ticket):
        print(f"票据申请失败: {ticket.get('code')} {ticket.get('message')}", file=sys.stderr)
        return 4
    data = ticket.get("data") or {}
    publish_url = data.get("publishUrl")
    if not publish_url:
        print("后端未返回 publishUrl", file=sys.stderr)
        return 5

    print(f"登录成功，推流会话={data.get('streamSessionNo')}，票据有效期={data.get('expiresAt')}")
    print("开始推流（安全起见不输出带 token 的完整 URL）……")
    if args.input:
        command = [ffmpeg, "-re", "-stream_loop", "-1", "-i", args.input,
                   "-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac", "-f", "flv", publish_url]
    else:
        command = [ffmpeg, "-re", "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=25",
                   "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
                   "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                   "-c:a", "aac", "-shortest", "-f", "flv", publish_url]
    return subprocess.call(command)


if __name__ == "__main__":
    raise SystemExit(main())
