#!/usr/bin/env bash
# ============================================================================
# PDK 阿里云部署 —— 本机一键入口（Windows Git Bash / macOS / Linux 均可运行）
#
# 流程：
#   1. 打包源码（排除 node_modules / target / 日志 / 私钥）
#   2. scp 到服务器 /opt/pdk
#   3. 依次在服务器上执行：
#        01-prepare-server.sh  装 JDK17 / Maven / Node22 / Nginx / Docker
#        02-init-infra.sh      生成密钥与 .env、起 MySQL+Redis、建表、设管理员密码
#        03-build.sh           mvn package + npm build（服务器编译）
#        04-deploy.sh          切版本上线 + systemd + Nginx + 健康检查
#
# 用法：
#   cp env.example.sh env.sh && vi env.sh        # 首次：填 IP 与 SSH 信息
#   bash deploy.sh --precheck                    # 先体检服务器（推荐首次执行）
#   bash deploy.sh                               # 完整部署
#   bash deploy.sh --prepare                     # 只做服务器环境初始化
#   bash deploy.sh --deploy-only                 # 不重新编译，直接切换已构建版本
#   bash deploy.sh --build                       # 只重新编译
#   bash deploy.sh --status                      # 查看线上状态
#   bash deploy.sh --rollback 20260904-140000    # 回滚
#   bash deploy.sh --skip-typecheck              # 前端跳过类型检查
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="${SCRIPT_DIR}/server"
LOCAL_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"      # 仓库根 E:/pdk
ENV_FILE="${SCRIPT_DIR}/env.sh"

# ------------------------------------------------------------------ 读取配置
if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少配置文件。请先：cp env.example.sh env.sh  然后填写 SERVER_IP / SERVER_USER"
  exit 1
fi
# shellcheck disable=SC1090
. "${ENV_FILE}"

SERVER_IP="${SERVER_IP:?请在 env.sh 中填写 SERVER_IP}"
SERVER_USER="${SERVER_USER:-root}"
SSH_PORT="${SSH_PORT:-22}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_rsa}"
REMOTE_ROOT="${REMOTE_ROOT:-/opt/pdk}"
SERVER_PORT="${SERVER_PORT:-8080}"
PDK_PUBLIC_BASE_URL="${PDK_PUBLIC_BASE_URL:-http://${SERVER_IP}}"
PDK_SERVER_NAME="${PDK_SERVER_NAME:-${SERVER_IP}}"
PDK_MYSQL_ROOT_PASSWORD="${PDK_MYSQL_ROOT_PASSWORD:-}"
CERTBOT_EMAIL="${CERTBOT_EMAIL:-}"
JAVA_XMX="${JAVA_XMX:-448m}"

MODE="full"
ARG_VALUE=""
case "${1:-}" in
  --prepare)      MODE="prepare" ;;
  --infra)        MODE="infra" ;;
  --build)        MODE="build" ;;
  --deploy-only)  MODE="deploy" ;;
  --precheck)     MODE="precheck" ;;
  --status)       MODE="status" ;;
  --rollback)     MODE="rollback"; ARG_VALUE="${2:-}" ;;
  --full)         MODE="full" ;;
  "")             MODE="full" ;;
  *) echo "未知参数: $1（支持 --precheck --prepare --infra --build --deploy-only --status --rollback <版本>）" >&2; exit 1 ;;
esac
SKIP_TYPECHECK="no"
MIGRATE_FLAG=""
# 注意：必须用 if 而非 [[ ]] && —— set -e 下条件为假会让脚本静默退出
for a in "$@"; do
  if [[ "$a" == "--skip-typecheck" ]]; then SKIP_TYPECHECK="yes"; fi
  if [[ "$a" == "--migrate" ]]; then MIGRATE_FLAG="--migrate"; fi
done
if [[ "${SKIP_PREPARE:-no}" == "yes" ]]; then MODE="${MODE/full/infra-build-deploy}"; fi

log()  { printf '\033[0;32m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[deploy]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[deploy][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

REMOTE_HOST="${SERVER_USER}@${SERVER_IP}"
SSH_OPTS=(-p "${SSH_PORT}" -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new -o BatchMode=yes)
if [[ -f "${SSH_KEY}" ]]; then SSH_OPTS+=(-i "${SSH_KEY}"); fi
SCP_OPTS=(-P "${SSH_PORT}" -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new -o BatchMode=yes)
if [[ -f "${SSH_KEY}" ]]; then SCP_OPTS+=(-i "${SSH_KEY}"); fi

# ------------------------------------------------------------------ 工具检查
for c in ssh scp tar; do command -v "$c" >/dev/null || die "缺少命令: $c"; done

# ------------------------------------------------------------------ SSH 连通性
log "测试 SSH 连接 ${REMOTE_HOST}:${SSH_PORT} ..."
if ! ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" "echo ok" >/dev/null 2>&1; then
  cat <<EOF
无法免密登录 ${REMOTE_HOST}。请先配置 SSH 公钥：

  1) 本机生成密钥（已有可跳过）：
       ssh-keygen -t ed25519 -f ${SSH_KEY}
  2) 把公钥传到服务器（会提示输入一次服务器密码）：
       ssh-copy-id -p ${SSH_PORT} ${REMOTE_HOST}
     Windows 没有 ssh-copy-id 时用这条：
       cat ${SSH_KEY}.pub | ssh -p ${SSH_PORT} ${REMOTE_HOST} "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
  3) 若私钥不在 ${SSH_KEY}，请在 env.sh 中设置 SSH_KEY=实际路径
EOF
  exit 1
fi
log "SSH 连通，远程用户: $(ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" 'whoami')@$(ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" 'hostname')"

# ------------------------------------------------- 只体检不动手（--precheck）
if [[ "${MODE}" == "precheck" ]]; then
  echo
  log "=========== 服务器体检报告 ==========="
  # shellcheck disable=SC2029
  ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" 'bash -se' <<'PRECHECK'
    echo "操作系统 : $(. /etc/os-release 2>/dev/null && echo "$PRETTY_NAME" || cat /etc/redhat-release 2>/dev/null)"
    echo "内核     : $(uname -r)"
    echo "CPU 核数 : $(nproc)"
    echo "内存     : $(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 )) MB"
    echo "磁盘剩余 : $(df -h / | awk 'NR==2{print $4}') 可用 / 共 $(df -h / | awk 'NR==2{print $2}')"
    echo
    echo "--- 组件检查 ---"
    for c in java mvn node npm nginx docker; do
      if command -v "$c" >/dev/null 2>&1; then
        case "$c" in
          java)   v="$(java -version 2>&1 | head -1)";;
          mvn)    v="Apache Maven $(mvn -v 2>/dev/null | awk 'NR==1{print $3}')";;
          node)   v="$(node -v 2>/dev/null)";;
          npm)    v="npm $(npm -v 2>/dev/null)";;
          nginx)  v="$(nginx -v 2>&1 | sed 's/nginx version: //')";;
          docker) v="$(docker -v 2>/dev/null | awk '{print $3}')";;
          *)      v="";;
        esac
        printf "  [已装] %-7s %s\n" "$c" "$v"
      else
        printf "  [缺失] %-7s 01-prepare-server.sh 会安装\n" "$c"
      fi
    done
    echo
    echo "--- 端口占用 ---"
    for p in 80 8080 3306 6379; do
      if ss -tln 2>/dev/null | grep -q ":$p " || netstat -tln 2>/dev/null | grep -q ":$p "; then
        printf "  %-6s 已占用\n" "$p"
      else
        printf "  %-6s 空闲\n" "$p"
      fi
    done
PRECHECK
  echo
  log "按上面『内存』一项调整 env.sh 的 JAVA_XMX：2GB→512m，4GB→1536m，8GB→3g"
  echo
  log "体检完成。确认无误后执行：bash deploy/aliyun/deploy.sh"
  exit 0
fi

# ------------------------------------------------------------------ 上传脚本
log "上传运维脚本 -> ${REMOTE_ROOT}/scripts/"
ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" "mkdir -p ${REMOTE_ROOT}/scripts ${REMOTE_ROOT}/config"
scp "${SCP_OPTS[@]}" -q "${SERVER_DIR}"/*.sh "${SERVER_DIR}"/*.yml "${SERVER_DIR}"/*.conf "${SERVER_DIR}"/*.py \
  "${REMOTE_HOST}:${REMOTE_ROOT}/scripts/" 2>/dev/null \
  || for f in "${SERVER_DIR}"/*; do scp "${SCP_OPTS[@]}" -q "$f" "${REMOTE_HOST}:${REMOTE_ROOT}/scripts/"; done
# Windows 编辑器可能写入 CRLF，Linux 上会让 bash 报 ^M 错误
ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" \
  "sed -i 's/\r$//' ${REMOTE_ROOT}/scripts/*.sh ${REMOTE_ROOT}/scripts/*.yml ${REMOTE_ROOT}/scripts/*.conf ${REMOTE_ROOT}/scripts/*.py 2>/dev/null; \
   chmod +x ${REMOTE_ROOT}/scripts/*.sh; echo '脚本已修正换行符并赋予执行权限'"

# ------------------------------------------------------------------ 打包源码
upload_source() {
  local tar_file="/tmp/pdk-src-$(date +%Y%m%d-%H%M%S).tar.gz"
  log "打包源码（排除 node_modules / target / 日志 / 私钥）..."
  tar -czf "${tar_file}" \
    --exclude='node_modules' \
    --exclude='target' \
    --exclude='.git' \
    --exclude='.workbuddy' \
    --exclude='.idea' \
    --exclude='dist' \
    --exclude='__pycache__' \
    --exclude='*.log' \
    --exclude='hs_err_pid*' \
    --exclude='replay_pid*' \
    --exclude='vite.config.ts.timestamp-*' \
    --exclude='backend-springboot/config/client-update-keys.yml' \
    -C "${LOCAL_ROOT}" backend-springboot admin-vue3 scripts
  local size; size=$(du -h "${tar_file}" | cut -f1)
  log "源码包: ${tar_file} (${size})"

  log "上传源码包..."
  scp "${SCP_OPTS[@]}" -q "${tar_file}" "${REMOTE_HOST}:/tmp/pdk-src.tar.gz"

  log "解压到 ${REMOTE_ROOT}/src ..."
  ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" \
    "mkdir -p ${REMOTE_ROOT}/src && tar -xzf /tmp/pdk-src.tar.gz -C ${REMOTE_ROOT}/src && rm -f /tmp/pdk-src.tar.gz && echo '解压完成'"
  rm -f "${tar_file}"
}

# ------------------------------------------------------------------ 上传升级密钥
upload_keys() {
  local local_keys="${LOCAL_ROOT}/backend-springboot/config/client-update-keys.yml"
  if [[ -f "${local_keys}" ]]; then
    log "上传升级签名密钥（客户端已内置该公钥，换密钥会导致客户端验签失败）"
    scp "${SCP_OPTS[@]}" -q "${local_keys}" "${REMOTE_HOST}:${REMOTE_ROOT}/config/client-update-keys.yml.staged"
    ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" "chmod 600 ${REMOTE_ROOT}/config/client-update-keys.yml.staged"
  else
    warn "本机没有 config/client-update-keys.yml，服务器将现场生成新密钥"
    warn "新公钥需同步到客户端，否则所有客户端无法升级"
  fi
}

# ------------------------------------------------------------------ 远程执行
run_remote() {
  local script="$1"; shift
  # 环境变量经 ssh 传递时由远端 shell 重新解析，值里的空格/引号必须转义，
  # 否则 ADMIN_INIT_PASSWORD 含特殊字符时会被拆成多个词导致执行失败。
  local env_prefix="" k v
  for kv in "$@"; do
    k="${kv%%=*}"; v="${kv#*=}"
    v="${v//\'/\'\\\'\'}"
    env_prefix+="${k}='${v}' "
  done
  log ">>> 服务器执行 ${script}"
  # shellcheck disable=SC2029
  ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" \
    "${env_prefix} PDK_ROOT=${REMOTE_ROOT} bash ${REMOTE_ROOT}/scripts/${script}"
}

case "${MODE}" in
  prepare)
    run_remote 01-prepare-server.sh
    ;;
  infra)
    upload_keys
    run_remote 02-init-infra.sh "PDK_PUBLIC_BASE_URL=${PDK_PUBLIC_BASE_URL}" "ADMIN_INIT_PASSWORD=${ADMIN_INIT_PASSWORD:-}" "JAVA_XMX=${JAVA_XMX}" "PDK_MYSQL_ROOT_PASSWORD=${PDK_MYSQL_ROOT_PASSWORD}"
    ;;
  build)
    upload_source
    run_remote 03-build.sh
    ;;
  deploy)
    run_remote "04-deploy.sh ${MIGRATE_FLAG}" "PDK_SERVER_NAME=${PDK_SERVER_NAME}" "PDK_PUBLIC_BASE_URL=${PDK_PUBLIC_BASE_URL}" "CERTBOT_EMAIL=${CERTBOT_EMAIL}"
    ;;
  status)
    run_remote 06-status.sh
    ;;
  rollback)
    run_remote "05-rollback.sh ${ARG_VALUE}"
    ;;
  full|infra-build-deploy)
    if [[ "${MODE}" == "full" ]]; then
      run_remote 01-prepare-server.sh
    fi
    upload_source
    upload_keys
    run_remote 02-init-infra.sh "PDK_PUBLIC_BASE_URL=${PDK_PUBLIC_BASE_URL}" "ADMIN_INIT_PASSWORD=${ADMIN_INIT_PASSWORD:-}" "JAVA_XMX=${JAVA_XMX}" "PDK_MYSQL_ROOT_PASSWORD=${PDK_MYSQL_ROOT_PASSWORD}"
    if [[ "${SKIP_TYPECHECK}" == "yes" ]]; then
      ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" "PDK_ROOT=${REMOTE_ROOT} bash ${REMOTE_ROOT}/scripts/03-build.sh --skip-typecheck"
    else
      run_remote 03-build.sh
    fi
    run_remote "04-deploy.sh ${MIGRATE_FLAG}" "PDK_SERVER_NAME=${PDK_SERVER_NAME}" "PDK_PUBLIC_BASE_URL=${PDK_PUBLIC_BASE_URL}" "CERTBOT_EMAIL=${CERTBOT_EMAIL}"
    ;;
esac

if [[ "${MODE}" == "full" || "${MODE}" == "infra-build-deploy" || "${MODE}" == "deploy" ]]; then
  cat <<EOF

$(printf '\033[0;32m[deploy]\033[0m') 部署流程结束
  管理后台 : ${PDK_PUBLIC_BASE_URL}/
  超级管理员: 13454118762
  初始密码  : ${ADMIN_INIT_PASSWORD:+已在服务器输出}${ADMIN_INIT_PASSWORD:-随机生成，见服务器 ${REMOTE_ROOT}/.admin-init-password}

  查看状态  bash deploy.sh --status
  回滚版本  bash deploy.sh --rollback <版本>
EOF
fi
