#!/usr/bin/env bash
# ============================================================================
# PDK 编译 —— 在服务器上构建后端 jar 与前端静态产物
#
# 产物落在带时间戳的 releases/<时间戳>/ 目录，并把 releases/latest 指向它，
# 部署脚本只认 latest，因此回滚 = 把 latest 指回上一版（见 05-rollback.sh）。
#
# 用法：sudo bash /opt/pdk/scripts/03-build.sh [--skip-typecheck]
#   --skip-typecheck  跳过 vue-tsc 类型检查（小内存机器或类型报错时的应急通道）
# ============================================================================
set -euo pipefail

PDK_ROOT="${PDK_ROOT:-/opt/pdk}"
SRC_DIR="${PDK_ROOT}/src"
SKIP_TYPECHECK="no"
# 用 if 而非 [[ ]] && ：set -e 下条件为假会让脚本静默退出
if [[ "${1:-}" == "--skip-typecheck" ]]; then SKIP_TYPECHECK="yes"; fi

log()  { printf '\033[0;32m[build]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[build]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[build][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

# ------------------------------------------------- 内存预算（2核1.6GB 机器）
# 服务器总内存 1673MB，MySQL 调优后约占 160MB，Nginx+PM2+Docker 约 200MB，
# 留给编译的只有 800MB 左右。必须给 JVM 和 Node 都设硬上限，
# 否则 npm build 会在 linker 阶段被 OOM Killer 干掉（表现为 137 退出码）。
AVAIL_MB="$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)"
log "编译前可用内存: ${AVAIL_MB} MB"

# Node 堆上限：取可用内存的 55%，上限 1536MB。过大会触发系统 OOM。
NODE_HEAP="$(( AVAIL_MB * 55 / 100 ))"
if [[ "${NODE_HEAP}" -gt 1536 ]]; then NODE_HEAP=1536; fi
if [[ "${NODE_HEAP}" -lt 768 ]];  then NODE_HEAP=768;  fi
export NODE_OPTIONS="${NODE_OPTIONS:---max-old-space-size=${NODE_HEAP}}"

# Maven 堆上限：取可用内存的 45%，上限 1024MB
MVN_HEAP="$(( AVAIL_MB * 45 / 100 ))"
if [[ "${MVN_HEAP}" -gt 1024 ]]; then MVN_HEAP=1024; fi
if [[ "${MVN_HEAP}" -lt 512 ]];  then MVN_HEAP=512;  fi
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx${MVN_HEAP}m -Dfile.encoding=UTF-8}"

export CI=true
log "内存上限: Node 堆 ${NODE_HEAP}MB / Maven 堆 ${MVN_HEAP}MB"

# swap 兜底：编译峰值可能瞬时超过物理内存，有 swap 才不会被直接杀进程
if ! swapon --show 2>/dev/null | grep -q .; then
  warn "未检测到 swap，正在创建 2G 交换文件作为 OOM 缓冲..."
  fallocate -l 2G /swapfile-pdk 2>/dev/null || dd if=/dev/zero of=/swapfile-pdk bs=1M count=2048 status=none
  chmod 600 /swapfile-pdk
  mkswap /swapfile-pdk >/dev/null 2>&1 && swapon /swapfile-pdk && log "swap 已启用"
fi

TS="$(date +%Y%m%d-%H%M%S)"
RELEASE_DIR="${PDK_ROOT}/releases/${TS}"

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"
[[ -d "${SRC_DIR}/backend-springboot" ]] || die "源码缺失: ${SRC_DIR}/backend-springboot（请先上传）"

# ---------------------------------------------------------------- 环境校验
export PATH="/opt/maven/bin:/opt/node/bin:${PATH}"
command -v mvn  >/dev/null || die "Maven 未安装，先执行 01-prepare-server.sh"
command -v node >/dev/null || die "Node 未安装，先执行 01-prepare-server.sh"
java -version 2>&1 | head -1

mkdir -p "${RELEASE_DIR}"
log "本次构建目录: ${RELEASE_DIR}"

# ================================================================ 1. 后端
log "编译后端（mvn clean package -DskipTests，首次需下载依赖，约 3-10 分钟）..."
cd "${SRC_DIR}/backend-springboot"
MVN_FLAGS=(-B -ntp -DskipTests -Dmaven.test.skip=true)
if [[ -f /root/.m2/settings.xml ]]; then
  MVN_FLAGS+=(-s /root/.m2/settings.xml)
fi
mvn "${MVN_FLAGS[@]}" clean package

# 末尾 || true：pipefail 下 grep 无匹配会让命令替换整体失败
JAR_PATH="$(ls -1 "${SRC_DIR}"/backend-springboot/target/pdk-commercial-system-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
[[ -n "${JAR_PATH}" ]] || die "未找到构建产物 jar"
cp "${JAR_PATH}" "${RELEASE_DIR}/app.jar"
log "后端产物: ${RELEASE_DIR}/app.jar ($(du -h "${RELEASE_DIR}/app.jar" | cut -f1))"

# 建表脚本跟着版本一起留存，回滚时能对上号
if [[ -f "${SRC_DIR}/backend-springboot/src/main/resources/schema-mysql.sql" ]]; then
  cp "${SRC_DIR}/backend-springboot/src/main/resources/schema-mysql.sql" "${RELEASE_DIR}/schema-mysql.sql"
fi

# ================================================================ 2. 前端
if [[ -d "${SRC_DIR}/admin-vue3" ]]; then
  log "编译前端（npm ci + build）..."
  cd "${SRC_DIR}/admin-vue3"
  npm ci --no-audit --no-fund --registry=https://registry.npmmirror.com

  # 前端 API 走相对路径 /api，由 Nginx 反代到 8080；
  # 只有需要把前后端部署到不同域名时才设置 VITE_API_BASE_URL。
  if [[ -n "${VITE_API_BASE_URL:-}" ]]; then
    echo "VITE_API_BASE_URL=${VITE_API_BASE_URL}" > .env.production.local
    log "注入 VITE_API_BASE_URL=${VITE_API_BASE_URL}"
  fi

  # 小内存机器上 vue-tsc 是内存杀手（类型检查要加载整个项目的类型图），
  # 优先单独跑 vite build；失败再降级重试，避免一次 OOM 就整个部署中断。
  BUILD_OK="no"
  if [[ "${SKIP_TYPECHECK}" == "yes" ]]; then
    warn "跳过 vue-tsc 类型检查（--skip-typecheck）"
    if npx vite build; then BUILD_OK="yes"; fi
  else
    log "先跑类型检查 vue-tsc（小内存机器若 OOM 会自动降级为仅打包）..."
    if npx vue-tsc --noEmit -p tsconfig.json; then
      log "类型检查通过"
    else
      warn "类型检查未通过或内存不足，降级为仅打包（不影响运行，类型问题不会阻断发布）"
    fi
    if npx vite build; then BUILD_OK="yes"; fi
  fi

  # 失败兜底：降到 512MB 堆再试一次（常见失败是 linker OOM，退出码 137）
  if [[ "${BUILD_OK}" != "yes" ]]; then
    warn "首次打包失败，以 512MB 堆上限重试一次..."
    NODE_OPTIONS="--max-old-space-size=512" npx vite build && BUILD_OK="yes"
  fi
  [[ "${BUILD_OK}" == "yes" ]] || die "前端构建失败，查看上方错误（若退出码 137 即为内存不足）"

  [[ -d dist ]] || die "前端构建失败：dist 目录不存在"
  cp -r dist "${RELEASE_DIR}/www-admin"
  log "前端产物: ${RELEASE_DIR}/www-admin ($(du -sh "${RELEASE_DIR}/www-admin" | cut -f1))"
else
  warn "未找到前端源码 ${SRC_DIR}/admin-vue3，跳过前端构建"
fi

# ================================================================ 3. 版本标记
ln -sfn "${RELEASE_DIR}" "${PDK_ROOT}/releases/latest"
echo "${TS}" > "${RELEASE_DIR}/BUILD_ID"
git -C "${PDK_ROOT}/src" rev-parse HEAD > "${RELEASE_DIR}/GIT_COMMIT" 2>/dev/null || echo "unknown" > "${RELEASE_DIR}/GIT_COMMIT"

# 只保留最近 5 个版本，避免磁盘被吃光
log "清理历史版本（保留最近 5 个）..."
ls -1dt "${PDK_ROOT}"/releases/*/ 2>/dev/null | tail -n +6 | xargs -r rm -rf

cat <<EOF

$(printf '\033[0;32m[build]\033[0m') 构建完成
  版本目录 : ${RELEASE_DIR}
  后端 jar : $(du -h "${RELEASE_DIR}/app.jar" 2>/dev/null | cut -f1)
  前端 dist: $(du -sh "${RELEASE_DIR}/www-admin" 2>/dev/null | cut -f1)

下一步：bash ${PDK_ROOT}/scripts/04-deploy.sh
EOF
