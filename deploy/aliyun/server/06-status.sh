#!/usr/bin/env bash
# ============================================================================
# PDK 运行状态总览 —— 排查问题的第一站
# 用法：sudo bash /opt/pdk/scripts/06-status.sh [--logs N]
# ============================================================================
set -uo pipefail

PDK_ROOT="${PDK_ROOT:-/opt/pdk}"
SERVICE_NAME="pdk-backend"
LOG_LINES="${LOG_LINES:-30}"
[[ "${1:-}" == "--logs" ]] && LOG_LINES="${2:-30}"

C_G='\033[0;32m'; C_Y='\033[0;33m'; C_R='\033[0;31m'; C_C='\033[0;36m'; C_0='\033[0m'
sec() { printf "\n${C_C}===== %s =====${C_0}\n" "$*"; }
ok()  { printf "${C_G}  [OK]${C_0}   %s\n" "$*"; }
bad() { printf "${C_R}  [FAIL]${C_0} %s\n" "$*"; }
warn_(){ printf "${C_Y}  [WARN]${C_0} %s\n" "$*"; }

# shellcheck disable=SC1090
[[ -f "${PDK_ROOT}/.env" ]] && { set -a; . "${PDK_ROOT}/.env"; set +a; }
PORT="${SERVER_PORT:-8080}"

sec "应用服务"
if systemctl is-active --quiet "${SERVICE_NAME}"; then
  ok "${SERVICE_NAME} 运行中 (PID $(systemctl show -p MainPID --value ${SERVICE_NAME}))"
else
  bad "${SERVICE_NAME} 未运行"
fi
systemctl is-enabled --quiet "${SERVICE_NAME}" 2>/dev/null && ok "已设置开机自启" || warn_ "未设置开机自启"
echo "  当前版本: $(cat "${PDK_ROOT}/releases/latest/BUILD_ID" 2>/dev/null || echo '未知')"
echo "  运行时长: $(systemctl show -p ActiveEnterTimestamp --value ${SERVICE_NAME} 2>/dev/null | cut -d' ' -f2- || echo '-')"

sec "健康检查"
HEALTH="$(curl -fsS --max-time 5 "http://127.0.0.1:${PORT}/actuator/health" 2>/dev/null || echo '')"
[[ -n "${HEALTH}" ]] && ok "后端 ${HEALTH}" || bad "后端 /actuator/health 无响应（端口 ${PORT}）"
API_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1/api/v1/admin/auth/me" || echo 000)"
[[ "${API_CODE}" == "000" ]] && bad "Nginx 反代不通" || ok "Nginx 反代 /api -> HTTP ${API_CODE}（401/403 都属正常）"
WEB_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1/" || echo 000)"
[[ "${WEB_CODE}" == "200" ]] && ok "管理后台首页 HTTP 200" || bad "管理后台首页 HTTP ${WEB_CODE}"

sec "基础设施（MySQL 复用本机实例 / Redis 容器）"
docker ps --format '  {{.Names}}\t{{.Status}}\t{{.Ports}}' 2>/dev/null | grep -E 'pdk-' || bad "Redis 容器未运行"
# MySQL 用的是服务器上已有的实例，不是容器
if [[ -n "${PDK_MYSQL_ROOT_PASSWORD:-}" ]]; then
  mysql -uroot -p"${PDK_MYSQL_ROOT_PASSWORD}" -e "SELECT 1;" >/dev/null 2>&1 \
    && ok "MySQL 可连接（本机实例）" || warn_ "MySQL 连接失败"
else
  mysql -uroot -e "SELECT 1;" >/dev/null 2>&1 \
    && ok "MySQL 可连接（本机实例）" || warn_ "MySQL 连接失败"
fi
mysql -uroot -e "SHOW DATABASES LIKE 'pdk_biz_db';" 2>/dev/null | grep -q pdk_biz_db \
  && ok "数据库 pdk_biz_db 已存在" || warn_ "数据库 pdk_biz_db 不存在"
docker exec pdk-redis redis-cli -a "${SPRING_DATA_REDIS_PASSWORD:-}" ping 2>/dev/null | grep -q PONG \
  && ok "Redis PONG" || warn_ "Redis 连接检查跳过或失败"

sec "升级密钥配置"
KEYS="${PDK_ROOT}/config/client-update-keys.yml"
if [[ -f "${KEYS}" ]]; then
  ok "密钥文件存在 ($(stat -c '%a %s bytes' "${KEYS}"))"
  grep -qE '^\s*artifact-private-key:\s*""' "${KEYS}" && warn_ "构件私钥为空 → 发布构件会报 50390" || ok "构件私钥已配置"
  grep -qE '^\s*policy-private-key:\s*""' "${KEYS}"   && warn_ "策略私钥为空 → 签发策略会报 50390" || ok "策略私钥已配置"
  grep -oE '^\s*public-base-url:.*' "${KEYS}" | sed 's/^/  /'
  grep -oE '^\s*storage-root:.*'    "${KEYS}" | sed 's/^/  /'
else
  bad "缺少 ${KEYS}"
fi

sec "资源占用"
echo "  内存: $(free -h | awk '/^Mem:/ {print $3"/"$2}')  负载: $(cat /proc/loadavg | cut -d' ' -f1-3)"
df -h "${PDK_ROOT}" | tail -1 | sed 's/^/  磁盘 /opt: /'
echo "  升级包存储: $(du -sh "${PDK_ROOT}/data/client-updates" 2>/dev/null | cut -f1)"
systemctl show -p MemoryCurrent --value "${SERVICE_NAME}" 2>/dev/null | awk '{if($1>0) printf "  后端内存: %.0f MB\n", $1/1048576}'

sec "最近 ${LOG_LINES} 行后端日志"
if [[ -f "${PDK_ROOT}/logs/backend.log" ]]; then
  tail -n "${LOG_LINES}" "${PDK_ROOT}/logs/backend.log"
else
  journalctl -u "${SERVICE_NAME}" -n "${LOG_LINES}" --no-pager 2>/dev/null || echo "  （无日志）"
fi

printf "\n${C_C}提示${C_0} 实时日志: sudo journalctl -u %s -f  或  tail -f %s/logs/backend.log\n" "${SERVICE_NAME}" "${PDK_ROOT}"
