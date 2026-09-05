#!/usr/bin/env bash
# ============================================================================
# PDK 基础设施初始化 —— 生成密钥配置 / 启动 MySQL+Redis / 建表 / 重置管理员密码
#
# 幂等设计：
#   - /opt/pdk/.env 已存在则【不覆盖】，保证重复部署不会把管理员密码转坏
#     （密码摘要 = SHA256(pepper + ":" + 密码)，pepper 一变所有账号立即失效）
#   - 需要强制轮换密钥时：bash 02-init-infra.sh --rotate-secrets
#
# 用法：sudo bash /opt/pdk/scripts/02-init-infra.sh [--rotate-secrets]
# ============================================================================
set -euo pipefail

PDK_ROOT="${PDK_ROOT:-/opt/pdk}"
ENV_FILE="${PDK_ROOT}/.env"
STAGED_KEYS="${PDK_ROOT}/config/client-update-keys.yml.staged"
KEYS_FILE="${PDK_ROOT}/config/client-update-keys.yml"
COMPOSE_FILE="${PDK_ROOT}/infra-compose.yml"
SCHEMA_SQL="${PDK_ROOT}/src/backend-springboot/src/main/resources/schema-mysql.sql"
DB_NAME="pdk_biz_db"
DB_APP_USER="pdk"
ADMIN_USERNAME="13454118762"
ROTATE="no"
# 用 if 而非 [[ ]] && ：set -e 下条件为假会让脚本静默退出
if [[ "${1:-}" == "--rotate-secrets" ]]; then ROTATE="yes"; fi

log()  { printf '\033[0;32m[infra]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[infra]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[infra][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"
command -v docker >/dev/null || die "Docker 未安装，请先执行 01-prepare-server.sh"

rand_hex()  { openssl rand -hex 32; }
rand_pass() { openssl rand -base64 30 | tr -dc 'A-Za-z0-9' | head -c 24; }

# ================================================================ 1. 环境文件
if [[ -f "${ENV_FILE}" && "${ROTATE}" == "no" ]]; then
  log ".env 已存在，保持不变（密码/pepper 不变，管理员账号不会失效）"
else
  if [[ -f "${ENV_FILE}" && "${ROTATE}" == "yes" ]]; then
    warn "强制轮换密钥：备份旧 .env 到 ${ENV_FILE}.bak.$(date +%s)"
    cp "${ENV_FILE}" "${ENV_FILE}.bak.$(date +%s)"
    warn "轮换后旧密码全部失效，稍后会为 ${ADMIN_USERNAME} 重新设置初始密码"
  fi
  log "生成 ${ENV_FILE} ..."

  # 管理员初始密码：优先取环境变量，否则随机生成并打印（随机最安全）
  ADMIN_INIT_PASSWORD="${ADMIN_INIT_PASSWORD:-$(rand_pass)}"
  export ADMIN_INIT_PASSWORD
  # 明文另存（600），避免随机密码只在终端闪一次就丢失；不写进 .env
  echo "${ADMIN_INIT_PASSWORD}" > "${PDK_ROOT}/.admin-init-password"
  chmod 600 "${PDK_ROOT}/.admin-init-password"

  cat > "${ENV_FILE}" <<EOF
# ============================================================================
# PDK 生产环境变量（由 02-init-infra.sh 生成，勿手动编辑后覆盖生成逻辑）
# 修改后执行：sudo systemctl restart pdk-backend
# ============================================================================
TZ=Asia/Shanghai
SERVER_PORT=8080

# ---- 数据库（复用服务器上已有的 MySQL，不是 Docker 容器）----
# 本服务器 3306 已被本机 MySQL 8.0.46 占用，容器起不来，因此直接复用。
# 首次执行时若 root 需要密码，请把密码填在 PDK_MYSQL_ROOT_PASSWORD。
DB_URL="jdbc:mysql://127.0.0.1:3306/${DB_NAME}?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
DB_USER=${DB_APP_USER}
DB_PASS=$(rand_pass)
PDK_MYSQL_ROOT_PASSWORD=${PDK_MYSQL_ROOT_PASSWORD:-}

# ---- Redis ----
SPRING_DATA_REDIS_HOST=127.0.0.1
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=$(rand_pass)
SPRING_DATA_REDIS_DATABASE=0

# ---- 业务安全（生产随机值，切勿使用默认值）----
PDK_ADMIN_PASSWORD_PEPPER=$(rand_hex)
PDK_FP_SALT=$(rand_hex)
PDK_ENABLED_BIZ_CODES=PDD,ZHIBO

# ---- 日志字符集：application.yml 里写死了 GBK，Linux 必须覆盖为 UTF-8 ----
LOGGING_CHARSET_CONSOLE=UTF-8
LOGGING_CHARSET_FILE=UTF-8
PDK_MAPPER_LOG_LEVEL=WARN

# ---- 建表策略 ----
# application.yml 里写死 mode: always，即每次启动都跑一遍 schema-mysql.sql。
# 生产改为 never：表结构已由本脚本手工导入（第 4 步），避免 43KB 脚本在每次
# 重启时重复执行、且 continue-on-error:false 会让任何一条语句报错直接搞挂启动。
# 升级时若新版本带了新表：bash 04-deploy.sh --migrate
SPRING_SQL_INIT_MODE=never

# ---- 上传限制（客户端升级包可能很大）----
PDK_UPDATE_MAX_FILE_SIZE=512MB
PDK_UPDATE_MAX_REQUEST_SIZE=520MB

# ---- JVM ----
# 服务器总内存 1.6GB，MySQL 已调优到 160MB，可用约 1GB。
# 448m 是留给业务的安全值（JVM 实际 RSS 约 550MB），留出余量给 Redis 和编译过程。
JAVA_OPTS="-Xms192m -Xmx${JAVA_XMX:-448m} -XX:MaxMetaspaceSize=128m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${PDK_ROOT}/logs -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
EOF
  chmod 600 "${ENV_FILE}"
  log ".env 生成完毕（权限 600）"
fi

# shellcheck disable=SC1090
set -a; . "${ENV_FILE}"; set +a
[[ -n "${DB_PASS:-}" ]] || die ".env 缺少 DB_PASS"

# ================================================================ 2. 升级密钥
# client-update 的 storage-root / public-base-url / 签名密钥只能通过这个 yml 注入：
# application.yml 里除了上传大小外没有环境变量占位符，这是项目刻意的设计。
mkdir -p "${PDK_ROOT}/config" && chmod 700 "${PDK_ROOT}/config"
PUBLIC_BASE_URL="${PDK_PUBLIC_BASE_URL:-http://$(curl -s --max-time 5 ifconfig.me 2>/dev/null || echo 127.0.0.1)}"
STORAGE_ROOT="${PDK_ROOT}/data/client-updates"

if [[ -f "${STAGED_KEYS}" ]]; then
  # 优先使用开发机上传的密钥：客户端已内置对应公钥，换密钥会导致所有客户端验签失败
  log "采用开发机上传的升级密钥（保留原有 key-id，客户端无需换公钥）"
  python3 - "${STAGED_KEYS}" "${KEYS_FILE}" "${STORAGE_ROOT}" "${PUBLIC_BASE_URL}" <<'PY'
import re, sys
src, dst, storage, base = sys.argv[1:5]
text = open(src, encoding='utf-8').read()
text = re.sub(r'(?m)^(\s*storage-root\s*:\s*).*$',
              lambda m: m.group(1) + '"%s"' % storage, text)
text = re.sub(r'(?m)^(\s*public-base-url\s*:\s*).*$',
              lambda m: m.group(1) + '"%s"' % base, text)
open(dst, 'w', encoding='utf-8').write(text)
print("    已改写 storage-root / public-base-url")
PY
  # 源码包里若夹带了私钥副本，就地删掉，避免私钥散落在普通权限目录
  rm -f "${PDK_ROOT}/src/backend-springboot/config/client-update-keys.yml"
  rm -f "${STAGED_KEYS}"
else
  if [[ -s "${KEYS_FILE}" ]]; then
    log "升级密钥已存在 ${KEYS_FILE}，保持不变"
  else
    log "未发现上传的密钥，在服务器上现场生成一套 Ed25519 密钥..."
    # cryptography 是生成 Ed25519 的硬依赖
    python3 -c "import cryptography" 2>/dev/null \
      || apt-get install -y -qq --no-install-recommends python3-cryptography >/dev/null 2>&1 \
      || pip3 install --break-system-packages -q -i https://mirrors.aliyun.com/pypi/simple/ cryptography >/dev/null 2>&1 \
      || die "无法安装 python cryptography，请手动执行: apt-get install -y python3-cryptography"

    python3 "${PDK_ROOT}/scripts/gen-update-keys.py" \
      --output "${KEYS_FILE}" \
      --storage-root "${STORAGE_ROOT}" \
      --public-base-url "${PUBLIC_BASE_URL}"
    warn "新生成的公钥需同步到客户端配置（client-pyqt/config/*.json 的 artifactPublicKeys/policyPublicKeys）"
    warn "公钥文件: ${PDK_ROOT}/config/client-update-public-keys.json"
  fi
fi
chmod 600 "${KEYS_FILE}"

# 启动前自检：私钥/公钥/key-id 三者必须同源，否则后端直接拒绝启动
if grep -qE '^\s*artifact-private-key:\s*""' "${KEYS_FILE}" 2>/dev/null; then
  warn "签名私钥为空：服务能启动，但「发布构件/签发升级策略」会返回 50390"
fi

# ================================================================ 3. 启动基础设施
# compose 模板随运维脚本一起上传到 scripts/ 下，使用前同步到 PDK_ROOT
if [[ ! -f "${COMPOSE_FILE}" && -f "${PDK_ROOT}/scripts/infra-compose.yml" ]]; then
  cp "${PDK_ROOT}/scripts/infra-compose.yml" "${COMPOSE_FILE}"
fi
[[ -f "${COMPOSE_FILE}" ]] || die "缺少 ${COMPOSE_FILE}（应随运维脚本上传）"

# 只起 Redis：MySQL 用服务器上已有的实例（3306 已被占用，容器起不来）
log "启动 Redis（docker compose，MySQL 复用本机实例）..."
cd "${PDK_ROOT}"
docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" up -d

# 本机 MySQL 连接助手：root 免密是阿里云镜像的常见默认，有密码则从 .env 取
mysql_root() {
  if [[ -n "${PDK_MYSQL_ROOT_PASSWORD:-}" ]]; then
    mysql -uroot -p"${PDK_MYSQL_ROOT_PASSWORD}" "$@"
  else
    mysql -uroot "$@"
  fi
}

log "检测本机 MySQL（复用，不新建容器）..."
MYSQL_READY="no"
for i in $(seq 1 30); do
  if mysql_root -e "SELECT 1;" >/dev/null 2>&1; then
    log "MySQL 就绪（第 ${i} 次探测，版本 $(mysql_root -N -e 'SELECT VERSION();' 2>/dev/null)）"
    MYSQL_READY="yes"
    break
  fi
  sleep 2
done
if [[ "${MYSQL_READY}" != "yes" ]]; then
  cat <<EOF
连不上本机 MySQL。请先确认：
  1) 服务是否运行： systemctl status mysql
  2) root 是否需要密码： mysql -uroot -e "SELECT 1;"
     若需要密码，在 ${ENV_FILE} 里设置 PDK_MYSQL_ROOT_PASSWORD=你的密码 后重跑本脚本
EOF
  exit 1
fi

log "等待 Redis 就绪..."
REDIS_READY="no"
for i in $(seq 1 30); do
  if docker exec pdk-redis redis-cli -a "${SPRING_DATA_REDIS_PASSWORD}" ping 2>/dev/null | grep -q PONG; then
    log "Redis 就绪"
    REDIS_READY="yes"
    break
  fi
  sleep 1
done
[[ "${REDIS_READY}" == "yes" ]] || die "Redis 启动超时，查看日志: docker logs pdk-redis"

# ================================================================ 4. 建库 + 导入表结构
db_exec() { mysql -u"${DB_APP_USER}" -p"${DB_PASS}" "${DB_NAME}" "$@"; }

log "创建数据库 ${DB_NAME} 与应用账号 ${DB_APP_USER}（若不存在）..."
mysql_root -e "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" 2>&1 | grep -v "Using a password" || true
mysql_root -e "CREATE USER IF NOT EXISTS '${DB_APP_USER}'@'%' IDENTIFIED BY '${DB_PASS}';" 2>&1 | grep -v "Using a password" || true
mysql_root -e "CREATE USER IF NOT EXISTS '${DB_APP_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';" 2>&1 | grep -v "Using a password" || true
mysql_root -e "ALTER USER '${DB_APP_USER}'@'%' IDENTIFIED BY '${DB_PASS}';" 2>&1 | grep -v "Using a password" || true
mysql_root -e "ALTER USER '${DB_APP_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';" 2>&1 | grep -v "Using a password" || true
mysql_root -e "GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_APP_USER}'@'%';" 2>&1 | grep -v "Using a password" || true
mysql_root -e "GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_APP_USER}'@'localhost';" 2>&1 | grep -v "Using a password" || true
mysql_root -e "FLUSH PRIVILEGES;" 2>&1 | grep -v "Using a password" || true
log "数据库与账号就绪"

if [[ -f "${SCHEMA_SQL}" ]]; then
  log "导入表结构 schema-mysql.sql（幂等，可重复执行）..."
  db_exec < "${SCHEMA_SQL}" 2>&1 | grep -v "Using a password" || true
  log "表结构导入完成"
else
  warn "未找到 ${SCHEMA_SQL}，跳过建表（后端启动时 spring.sql.init 会自动执行）"
fi

# ================================================================ 5. 管理员初始密码
# 摘要算法：SHA256(pepper + ":" + 明文)。.env 每次生成的 pepper 都不同，
# schema 里那个写死的 hash 是按默认 pepper 算的，不重算就永远登不进去。
ADMIN_HASH=$(ADMIN_PW="${ADMIN_INIT_PASSWORD:-}" PEPPER="${PDK_ADMIN_PASSWORD_PEPPER}" \
  python3 - <<'PY'
import hashlib, os
pw = os.environ.get("ADMIN_PW", "")
pepper = os.environ["PEPPER"]
print(hashlib.sha256(f"{pepper}:{pw}".encode("utf-8")).hexdigest())
PY
)

if [[ -n "${ADMIN_INIT_PASSWORD:-}" ]]; then
  # 密码里的单引号要转义，避免破坏 SQL 字面量
  ADMIN_SQL=$(ADMIN_HASH="${ADMIN_HASH}" ADMIN_USER="${ADMIN_USERNAME}" python3 - <<'PY'
import os
h, u = os.environ["ADMIN_HASH"], os.environ["ADMIN_USER"]
esc = lambda s: s.replace("\\", "\\\\").replace("'", "''")
print("UPDATE `pdk_admin_user` SET `password_hash`='%s', `status`='ACTIVE', `role_code`='SUPER_ADMIN' WHERE `username`='%s';"
      % (esc(h), esc(u)))
print("INSERT INTO `pdk_admin_user` (`username`,`password_hash`,`display_name`,`role_code`,`status`) "
      "SELECT '%s','%s','平台超级管理员','SUPER_ADMIN','ACTIVE' FROM DUAL "
      "WHERE NOT EXISTS (SELECT 1 FROM `pdk_admin_user` WHERE `username`='%s');"
      % (esc(u), esc(h), esc(u)))
PY
)
  echo "${ADMIN_SQL}" | db_exec 2>&1 | grep -v "Using a password" || true
  log "超级管理员 ${ADMIN_USERNAME} 的密码已按当前 pepper 重新计算"
  printf '\033[0;36m[infra]\033[0m 登录账号: %s  初始密码: %s\n' \
    "${ADMIN_USERNAME}" "${ADMIN_INIT_PASSWORD}"
  warn "请立即登录后台修改密码，并从此文件删除明文记录：${PDK_ROOT}/.env"
else
  log "未设置 ADMIN_INIT_PASSWORD，保持现有管理员密码不变"
fi

# ================================================================ 6. 汇总
cat <<EOF

$(printf '\033[0;32m[infra]\033[0m') 基础设施就绪
  MySQL   : 127.0.0.1:3306/${DB_NAME}（复用服务器已有实例，账号 ${DB_APP_USER}）
  Redis   : 127.0.0.1:6379（容器 pdk-redis，仅本机可连）
  升级密钥 : ${KEYS_FILE}
  存储目录 : ${STORAGE_ROOT}
  对外地址 : ${PUBLIC_BASE_URL}

下一步：bash ${PDK_ROOT}/scripts/03-build.sh
EOF
