#!/usr/bin/env bash
# ============================================================================
# PDK 部署 —— 把 releases/latest 的构建产物切上线（systemd + nginx）
#
# 特性：
#   - 切换前自动备份当前 jar 到 backups/
#   - 启动后做健康检查（/actuator/health），失败自动回滚并退出非 0
#   - 幂等：重复执行不会报错，只是重新切换一次
#
# 用法：sudo bash /opt/pdk/scripts/04-deploy.sh [--no-healthcheck] [--migrate]
#   --no-healthcheck  跳过健康检查（后端启动很慢时的临时通道）
#   --migrate         部署新版本前先同步表结构（新版本带了新表时用；schema 幂等可重复执行）
# ============================================================================
set -euo pipefail

PDK_ROOT="${PDK_ROOT:-/opt/pdk}"
RELEASE_DIR="${PDK_ROOT}/releases/latest"
APP_DIR="${PDK_ROOT}/app"
WWW_DIR="${PDK_ROOT}/www/admin"
BACKUP_DIR="${PDK_ROOT}/backups"
SERVICE_NAME="pdk-backend"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"
DO_HEALTHCHECK="yes"
DO_MIGRATE="no"
for arg in "$@"; do
  case "${arg}" in
    --no-healthcheck) DO_HEALTHCHECK="no" ;;
    --migrate)        DO_MIGRATE="yes" ;;
  esac
done

log()  { printf '\033[0;32m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[deploy]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[deploy][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"
[[ -d "${RELEASE_DIR}" ]] || die "没有可部署的版本: ${RELEASE_DIR}（先执行 03-build.sh）"
[[ -f "${RELEASE_DIR}/app.jar" ]] || die "版本目录缺少 app.jar: ${RELEASE_DIR}"

# 用 if 而非 [[ ]] && ：set -e 下条件为假会让脚本静默退出
if [[ -f "${PDK_ROOT}/.env" ]]; then set -a; . "${PDK_ROOT}/.env"; set +a; fi

mkdir -p "${APP_DIR}" "${WWW_DIR}" "${BACKUP_DIR}" "${PDK_ROOT}/logs"

# ---------------------------------------------------------------- 备份当前版本
if [[ -f "${APP_DIR}/app.jar" ]]; then
  BK="${BACKUP_DIR}/app.jar.$(date +%Y%m%d-%H%M%S).bak"
  cp "${APP_DIR}/app.jar" "${BK}"
  log "已备份当前 jar -> ${BK}"
fi

# ---------------------------------------------------------------- 切换后端
log "部署后端: ${RELEASE_DIR}/app.jar"
install -m 644 "${RELEASE_DIR}/app.jar" "${APP_DIR}/app.jar"

# ---------------------------------------------------------------- 切换前端
if [[ -d "${RELEASE_DIR}/www-admin" ]]; then
  log "部署前端静态文件 -> ${WWW_DIR}"
  # --delete 保证删掉旧版本遗留文件，避免新旧 hash 文件混存
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete "${RELEASE_DIR}/www-admin/" "${WWW_DIR}/"
  else
    rm -rf "${WWW_DIR:?}"/* && cp -r "${RELEASE_DIR}/www-admin/." "${WWW_DIR}/"
  fi
  log "前端文件数: $(find "${WWW_DIR}" -type f | wc -l)"
else
  warn "本版本没有前端产物，保留现有 ${WWW_DIR}"
fi

# ---------------------------------------------------------------- 表结构同步
# 生产 .env 里 SPRING_SQL_INIT_MODE=never，新版本若带来新表必须显式同步，
# 否则新代码启动后立刻报 "Table doesn't exist"。schema 幂等，可安全重复执行。
if [[ "${DO_MIGRATE}" == "yes" ]]; then
  DB_NAME="${PDK_DB_NAME:-pdk_biz_db}"
  # MySQL 是服务器上的已有实例，不是容器（3306 被本机 MySQL 占用）
  mysql_root() {
    if [[ -n "${PDK_MYSQL_ROOT_PASSWORD:-}" ]]; then
      mysql -uroot -p"${PDK_MYSQL_ROOT_PASSWORD}" "$@"
    else
      mysql -uroot "$@"
    fi
  }
  if [[ -f "${RELEASE_DIR}/schema-mysql.sql" ]] && mysql_root -e "SELECT 1;" >/dev/null 2>&1; then
    log "同步表结构 ${RELEASE_DIR}/schema-mysql.sql ..."
    mysql -u"${DB_USER:-pdk}" -p"${DB_PASS}" "${DB_NAME}" \
      < "${RELEASE_DIR}/schema-mysql.sql" 2>&1 | grep -v "Using a password" || true
    log "表结构同步完成"
  else
    warn "跳过表结构同步（缺少 schema-mysql.sql 或连不上本机 MySQL）"
  fi
fi

# ---------------------------------------------------------------- systemd
# 注意：systemd 里 \$VAR（不带花括号）才会按空格分词；\${VAR} 是单个参数，
# 会把整串 JVM 选项当作一个参数传给 java，报 Invalid initial heap size
JAVA_BIN="$(readlink -f "$(command -v java)")"
log "渲染 systemd unit（JVM: ${JAVA_BIN}）"
cat > "/etc/systemd/system/${SERVICE_NAME}.service" <<UNIT
[Unit]
Description=PDK Commercial System Backend
Documentation=file://${PDK_ROOT}
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=${PDK_ROOT}
EnvironmentFile=${PDK_ROOT}/.env
Environment=SERVER_PORT=${SERVER_PORT:-8080}
ExecStart=${JAVA_BIN} \$JAVA_OPTS -jar ${APP_DIR}/app.jar \\
  --spring.config.import=optional:file:${PDK_ROOT}/config/client-update-keys.yml \\
  --logging.charset.console=UTF-8 \\
  --logging.charset.file=UTF-8
ExecStop=/bin/kill -TERM \$MAINPID
SuccessExitStatus=143
Restart=always
RestartSec=10
TimeoutStopSec=30
StandardOutput=append:${PDK_ROOT}/logs/backend.log
StandardError=append:${PDK_ROOT}/logs/backend.log

# 基础加固：/tmp 独立、禁止提权
PrivateTmp=true
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}" >/dev/null 2>&1

# ---------------------------------------------------------------- Nginx + 证书
# 重要：这台服务器上 80 端口的 default_server 属于既有站点（live-chat）。
# 本脚本绝不删除、绝不替换 sites-enabled/default，只新增一个按域名匹配的
# server 块，与现有站点共存。删掉 default 会直接搞挂线上业务。
SERVER_NAME="${PDK_SERVER_NAME:-_}"
NGINX_AVAIL="/etc/nginx/sites-available/pdk"
NGINX_ENABLED="/etc/nginx/sites-enabled/pdk"
CERT_DIR="/etc/letsencrypt/live/${SERVER_NAME}"
mkdir -p /var/www/certbot/.well-known/acme-challenge

render_site() {
  # $1=模板文件  $2=输出文件
  sed -e "s#__PDK_ROOT__#${PDK_ROOT}#g" \
      -e "s#__SERVER_NAME__#${SERVER_NAME}#g" \
      -e "s#__BACKEND_PORT__#${SERVER_PORT:-8080}#g" \
      -e "s#__SSL_CERT__#${CERT_DIR}/fullchain.pem#g" \
      -e "s#__SSL_KEY__#${CERT_DIR}/privkey.pem#g" \
      "$1" > "$2"
}

# 安全切换：先备份 → 写入 → nginx -t → 失败立即还原；
# 校验通过才 reload。这样即使配置写错，也不会中断正在运行的 nginx。
safe_nginx_switch() {
  local newconf="$1"
  local had_old="no"
  if [[ -f "${NGINX_AVAIL}" ]]; then
    cp "${NGINX_AVAIL}" "${NGINX_AVAIL}.bak.$(date +%s)"
    had_old="yes"
  fi
  cp "${newconf}" "${NGINX_AVAIL}"
  ln -sfn "${NGINX_AVAIL}" "${NGINX_ENABLED}"

  if ! nginx -t 2>/tmp/nginx-t.err; then
    warn "新 Nginx 配置未通过校验，正在还原，现有站点不受影响："
    sed 's/^/    /' /tmp/nginx-t.err
    if [[ -f "${newconf}" ]]; then rm -f "${NGINX_ENABLED}"; fi
    if [[ "${had_old}" == "yes" ]]; then
      local latest_bak
      latest_bak="$(ls -1t "${NGINX_AVAIL}".bak.* 2>/dev/null | head -1 || true)"
      if [[ -n "${latest_bak}" ]]; then
        cp "${latest_bak}" "${NGINX_AVAIL}"
        ln -sfn "${NGINX_AVAIL}" "${NGINX_ENABLED}"
        nginx -t >/dev/null 2>&1 && systemctl reload nginx
        warn "已还原为上一个可用配置"
      fi
    fi
    return 1
  fi

  # 校验通过才真正生效；reload 是热加载，不会断开现有连接
  systemctl enable nginx >/dev/null 2>&1
  systemctl reload nginx
  return 0
}

# ---- 第一步：确保证书存在（没有就用引导配置走 HTTP-01 验证）----
HAVE_CERT="no"
if [[ -f "${CERT_DIR}/fullchain.pem" && -f "${CERT_DIR}/privkey.pem" ]]; then
  log "检测到已有证书 ${CERT_DIR}"
  HAVE_CERT="yes"
elif [[ "${SERVER_NAME}" == "_" || "${SERVER_NAME}" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  warn "server_name 是 IP(${SERVER_NAME})，Let's Encrypt 不给 IP 签发证书，跳过 HTTPS"
else
  log "为 ${SERVER_NAME} 申请 Let's Encrypt 证书（HTTP-01）..."
  render_site "${PDK_ROOT}/scripts/nginx-pdk-bootstrap.conf" /tmp/pdk-nginx-bootstrap.conf
  if ! safe_nginx_switch /tmp/pdk-nginx-bootstrap.conf; then
    die "引导用 Nginx 配置校验失败，无法申请证书"
  fi

  if certbot certonly --webroot -w /var/www/certbot \
       -d "${SERVER_NAME}" --non-interactive --agree-tos \
       --email "${CERTBOT_EMAIL:-admin@${SERVER_NAME#*.}}" --keep-until-expiring 2>&1 | tail -20; then
    if [[ -f "${CERT_DIR}/fullchain.pem" ]]; then
      log "✅ 证书签发成功"
      HAVE_CERT="yes"
    else
      warn "certbot 执行完毕但未找到证书文件，将以 HTTP 模式继续"
    fi
  else
    warn "证书签发失败（域名未解析 / 80 端口不通 / 已达限流），将以 HTTP 模式继续"
    warn "  排查后单独重签：certbot certonly --webroot -w /var/www/certbot -d ${SERVER_NAME}"
  fi
fi

# ---- 第二步：写入正式站点配置（有证书则 HTTPS，无则退回 HTTP）----
if [[ "${HAVE_CERT}" == "yes" ]]; then
  log "渲染 HTTPS 站点（server_name=${SERVER_NAME}）"
  render_site "${PDK_ROOT}/scripts/nginx-pdk.conf" /tmp/pdk-nginx.conf
else
  log "渲染 HTTP 站点（server_name=${SERVER_NAME}，无证书）"
  render_site "${PDK_ROOT}/scripts/nginx-pdk-bootstrap.conf" /tmp/pdk-nginx.conf
fi

if ! safe_nginx_switch /tmp/pdk-nginx.conf; then
  die "Nginx 配置切换失败，后端未受影响（详见上方错误信息）"
fi
rm -f /tmp/pdk-nginx.conf /tmp/pdk-nginx-bootstrap.conf /tmp/nginx-t.err
log "Nginx 站点已生效，现有站点（sites-enabled/default）保持不变"

# ---------------------------------------------------------------- 启动后端
log "重启 ${SERVICE_NAME} ..."
systemctl restart "${SERVICE_NAME}"

# ---------------------------------------------------------------- 健康检查
health_fail() {
  printf '\033[0;31m[deploy][ERROR]\033[0m %s\n' "$1" >&2
  warn "---- 服务最近 40 行日志 ----"
  tail -n 40 "${PDK_ROOT}/logs/backend.log" 2>/dev/null || journalctl -u "${SERVICE_NAME}" -n 40 --no-pager
  if [[ -n "${BK:-}" && -f "${BK}" ]]; then
    warn "自动回滚到 ${BK}"
    cp "${BK}" "${APP_DIR}/app.jar"
    systemctl restart "${SERVICE_NAME}" || true
  fi
  exit 1
}

if [[ "${DO_HEALTHCHECK}" == "yes" ]]; then
  log "健康检查（最多 ${HEALTH_TIMEOUT}s）..."
  OK="no"
  for i in $(seq 1 "$((HEALTH_TIMEOUT / 3))"); do
    if curl -fsS --max-time 3 "http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      OK="yes"; log "后端健康（第 ${i} 次探测，约 $((i*3))s）"; break
    fi
    # 进程已退出就没必要继续等
    if ! systemctl is-active --quiet "${SERVICE_NAME}"; then
      health_fail "后端进程已退出，启动失败"
    fi
    sleep 3
  done
  [[ "${OK}" == "yes" ]] || health_fail "健康检查超时"

  # 必须带 Host 头才能命中我们按域名匹配的 server 块，
  # 否则会落到既有站点的 default_server 上，测的就不是 PDK 了。
  HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
              -H "Host: ${SERVER_NAME}" "http://127.0.0.1/" || echo 000)
  # 200=HTTP 模式直接命中；301=HTTPS 模式跳转到 https，都算正常
  if [[ "${HTTP_CODE}" == "200" || "${HTTP_CODE}" == "301" ]]; then
    log "Nginx 站点响应正常（HTTP ${HTTP_CODE}，Host=${SERVER_NAME}）"
  else
    warn "Nginx 站点返回 HTTP ${HTTP_CODE}（预期 200 或 301）"
  fi

  API_CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
             -H "Host: ${SERVER_NAME}" "http://127.0.0.1/api/v1/admin/auth/me" || echo 000)
  log "反代连通性 /api/v1/admin/auth/me -> HTTP ${API_CODE}（401 表示链路正常）"
fi

PROTO="http"
# 必须写成 if：[[ ]] && 在条件为假时返回 1，配合 set -e 会让脚本在
# 打印部署总结之前就静默退出（HTTP 模式下必然触发）
if [[ "${HAVE_CERT}" == "yes" ]]; then PROTO="https"; fi

cat <<EOF

$(printf '\033[0;32m[deploy]\033[0m') 部署完成
  版本     : $(cat "${RELEASE_DIR}/BUILD_ID" 2>/dev/null || echo unknown)
  服务状态 : $(systemctl is-active ${SERVICE_NAME})
  管理后台 : ${PROTO}://${SERVER_NAME}/
  接口健康 : http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health
  站点配置 : ${NGINX_AVAIL}（既有站点 default 未改动）

常用命令：
  查看日志  sudo journalctl -u ${SERVICE_NAME} -f
  查看状态  bash ${PDK_ROOT}/scripts/06-status.sh
  回滚版本  bash ${PDK_ROOT}/scripts/05-rollback.sh
  证书续期  certbot renew（HTTP-01 路径已在 Nginx 中放行，可自动续期）
EOF
