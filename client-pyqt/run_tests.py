"""PDK 全链路校验器（命令行，无 GUI 依赖）。

用法：
  python run_tests.py                      # 使用环境变量 PDK_API_BASE（默认 http://localhost:8080）
  python run_tests.py -u http://host:8080  # 指定后端地址
  PDK_TEST_CARD_KEY=PDK-XXXX-XXXX-XXXX python run_tests.py   # 提供真实激活码以跑通 S3

校验器会依次执行 8 个功能场景 + 16 个边界测试，并打印 PASS/FAIL/SKIP 汇总。
"""
from __future__ import annotations

import argparse
import os
import sys
from typing import Optional

from pdk_client import default_device_id
from pdk_testrunner import Result, TestRunner

# 简易彩色输出（Windows 终端通常支持）
RESET = "\033[0m"
GREEN = "\033[32m"
RED = "\033[31m"
YELLOW = "\033[33m"
CYAN = "\033[36m"
BOLD = "\033[1m"


def _tag(r: Result) -> str:
    if r.skipped or r.passed is None:
        return f"{YELLOW}SKIP{RESET}"
    return f"{GREEN}PASS{RESET}" if r.passed else f"{RED}FAIL{RESET}"


def _print_block(title: str, results: list[Result]) -> tuple[int, int, int]:
    print(f"\n{BOLD}{CYAN}{'=' * 72}{RESET}")
    print(f"{BOLD}{CYAN}{title}{RESET}")
    print(f"{BOLD}{CYAN}{'=' * 72}{RESET}")
    passed = skipped = failed = 0
    for r in results:
        print(f"  [{_tag(r)}] {r.sid:<3} {r.name:<22} 期待: {r.expected}")
        if r.detail:
            print(f"         └─ {r.detail}")
        if r.skipped or r.passed is None:
            skipped += 1
        elif r.passed:
            passed += 1
        else:
            failed += 1
    return passed, failed, skipped


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="PDK 全链路校验器")
    parser.add_argument("-u", "--base-url", default=os.getenv("PDK_API_BASE", "http://localhost:8080"))
    parser.add_argument("-d", "--device-id", default=default_device_id())
    parser.add_argument("--app-id", type=int, default=int(os.getenv("PDK_APP_ID", "1")))
    parser.add_argument("--only", choices=["scenarios", "boundary", "all"], default="all")
    parser.add_argument("--phone", default="", help="指定注册/登录手机号（默认自动生成新号注册）")
    parser.add_argument("--password", default="", help="指定登录密码（默认自动生成）")
    parser.add_argument("--sms-code", default="", help="手动指定短信验证码（fixed-code 模式可自动回显则无需）")
    args = parser.parse_args(argv)

    print(f"{BOLD}PDK 全链路校验器{RESET}")
    print(f"后端地址 : {args.base_url}")
    print(f"设备标识 : {args.device_id}")
    print(f"业务AppID: {args.app_id}")
    print(f"测试手机 : {args.phone or '自动生成'}")
    print(f"短信验证 : {'手输' if args.sms_code else 'debugCode 回显 / 环境变量'}")
    print(f"激活码   : {'已配置' if os.getenv('PDK_TEST_CARD_KEY') else '未配置（S3 将跳过）'}")

    runner = TestRunner(base_url=args.base_url, device_id=args.device_id, app_id=args.app_id)
    runner.manual_phone = args.phone.strip()
    runner.manual_password = args.password
    runner.manual_sms_code = args.sms_code.strip()
    scenarios: list[Result] = []
    boundary: list[Result] = []

    if args.only in ("scenarios", "all"):
        scenarios = runner.run_all_scenarios()
    if args.only in ("boundary", "all"):
        boundary = runner.run_boundary_tests()

    total_pass = total_fail = total_skip = 0
    if scenarios:
        p, f, s = _print_block("功能场景（8 项）", scenarios)
        total_pass += p; total_fail += f; total_skip += s
    if boundary:
        p, f, s = _print_block("边界测试（16 项）", boundary)
        total_pass += p; total_fail += f; total_skip += s

    print(f"\n{BOLD}{'=' * 72}{RESET}")
    print(f"{BOLD}汇总  PASS={GREEN}{total_pass}{RESET}  "
          f"FAIL={RED}{total_fail}{RESET}  "
          f"SKIP={YELLOW}{total_skip}{RESET}  "
          f"总计={total_pass + total_fail + total_skip}{RESET}")
    print(f"{BOLD}{'=' * 72}{RESET}")
    if total_fail == 0:
        print(f"{GREEN}✅ 全部可执行用例通过（SKIP 表示需前置条件/真实数据）。{RESET}")
        return 0
    print(f"{RED}❌ 存在 {total_fail} 个失败用例，请检查后端或测试数据。{RESET}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
