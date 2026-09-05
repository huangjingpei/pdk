#!/usr/bin/env bash
# ============================================================================
# PDK 回滚 —— 把 releases/latest 指回历史版本并重新上线
#
# 用法：
#   sudo bash /opt/pdk/scripts/05-rollback.sh            # 回滚到上一个版本
#   sudo bash /opt/pdk/scripts/05-rollback.sh 20260904-140000
#   sudo bash /opt/pdk/scripts/05-rollback.sh --list     # 只列出可回滚版本
#
# 注意：只回滚应用（jar + 前端），不回滚数据库。schema 若含破坏性变更需人工处理。
# ============================================================================
set -euo pipefail

PDK_ROOT="${PDK_ROOT:-/opt/pdk}"
RELEASES_DIR="${PDK_ROOT}/releases"

log()  { printf '\033[0;32m[rollback]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[rollback]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[rollback][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"

list_releases() {
  echo "可用版本（按时间倒序，* 为当前）："
  local current
  current="$(basename "$(readlink -f "${RELEASES_DIR}/latest")" 2>/dev/null || echo '')"
  # 末尾 || true：pipefail 下 ls/head 无匹配会让整条管道失败并终止脚本
  (ls -1dt "${RELEASES_DIR}"/[0-9]* 2>/dev/null || true) | while read -r d; do
    # 循环体内一律用 if：set -e 下 `[[ ]] && cmd` 条件为假会终止整个脚本
    if [[ -z "${d}" ]]; then continue; fi
    b="$(basename "$d")"
    mark=" "
    if [[ "$b" == "$current" ]]; then mark="*"; fi
    printf '  %s %s  jar=%s  dist=%s\n' "$mark" "$b" \
      "$([[ -f "$d/app.jar" ]] && echo yes || echo 'NO ')" \
      "$([[ -d "$d/www-admin" ]] && echo yes || echo 'NO ')"
  done
}

case "${1:-}" in
  --list|-l)
    list_releases
    exit 0
    ;;
esac

TARGET="${1:-}"
if [[ -z "${TARGET}" ]]; then
  # 取倒数第二个版本（第一个是当前 latest 指向的）
  CURRENT="$(basename "$(readlink -f "${RELEASES_DIR}/latest")" 2>/dev/null || echo '')"
  TARGET="$( (ls -1dt "${RELEASES_DIR}"/[0-9]* 2>/dev/null || true) | while read -r d; do basename "$d"; done | grep -v "^${CURRENT}$" | head -1 || true)"
  [[ -n "${TARGET}" ]] || die "没有可回滚的历史版本"
  log "未指定版本，自动选择上一个: ${TARGET}"
fi

TARGET_DIR="${RELEASES_DIR}/${TARGET}"
[[ -d "${TARGET_DIR}" ]] || die "版本不存在: ${TARGET_DIR}"
[[ -f "${TARGET_DIR}/app.jar" ]] || die "该版本缺少 app.jar: ${TARGET_DIR}"

warn "即将回滚到 ${TARGET}（仅回滚应用，不回滚数据库）"
ln -sfn "${TARGET_DIR}" "${RELEASES_DIR}/latest"
log "releases/latest -> ${TARGET}"

exec bash "${PDK_ROOT}/scripts/04-deploy.sh"
