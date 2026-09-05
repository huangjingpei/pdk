#!/usr/bin/env bash
# ============================================================================
# PDK 服务器初始化 —— 在阿里云 Ubuntu 22.04/24.04 上准备运行环境
#
# 幂等设计：可重复执行，已装好的组件会自动跳过。
# 用法：sudo bash /opt/pdk/scripts/01-prepare-server.sh
#
# 安装内容：
#   - OpenJDK 17（Spring Boot 3.3.2 要求 JDK17+）
#   - Maven 3.9.9（官方二进制 + 阿里云镜像，不用 apt 老版本）
#   - Node.js 22（npmmirror 二进制，前端 vite build 用）
#   - Nginx（静态托管前端 + 反代 /api）
#   - Docker + compose 插件（仅用于跑 MySQL / Redis）
#   - 目录骨架 /opt/pdk/**
# ============================================================================
set -euo pipefail

PDK_ROOT="${PDK_ROOT:-/opt/pdk}"
LOG_DIR="${PDK_ROOT}/logs"
MVN_VERSION="3.9.9"
NODE_VERSION="22.11.0"

# 全部 apt/网络操作走 noninteractive，避免弹窗卡住无人值守部署
export DEBIAN_FRONTEND=noninteractive
export LANG=C.UTF-8

log()  { printf '\033[0;32m[prepare]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[prepare]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[prepare][ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 root 执行（sudo bash $0）"

# ---------------------------------------------------------------- 1. 系统信息
# shellcheck disable=SC1091
. /etc/os-release
log "系统: ${PRETTY_NAME:-unknown} / 内核: $(uname -r) / 架构: $(uname -m)"
[[ "${ID:-}" == "ubuntu" || "${ID_LIKE:-}" == *debian* ]] \
  || warn "本脚本面向 Ubuntu/Debian，当前为 ${ID:-未知}，apt 步骤可能失败"

MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
DISK_FREE_GB=$(df -BG /opt | awk 'NR==2 {gsub("G","");print $4}')
log "内存: ${MEM_MB}MB / /opt 可用磁盘: ${DISK_FREE_GB}GB"
(( DISK_FREE_GB >= 10 )) || warn "磁盘剩余不足 10GB，Maven 依赖 + Docker 镜像可能装不下"

# ---------------------------------------------------------------- 2. 目录骨架
log "创建目录骨架 ${PDK_ROOT}/..."
mkdir -p "${PDK_ROOT}"/{src,app,releases,www/admin,logs,config,scripts,backups}
mkdir -p "${PDK_ROOT}"/data/{mysql,redis,client-updates}
chmod 700 "${PDK_ROOT}/config"          # 存放升级签名私钥
chmod 755 "${PDK_ROOT}/data/client-updates"

# ---------------------------------------------------------------- 3. 系统包
log "apt update + 安装基础包（首次较慢，约 2-5 分钟）..."
apt-get update -qq
apt-get install -y -qq --no-install-recommends \
  ca-certificates curl wget gnupg lsb-release unzip xz-utils \
  python3 python3-venv python3-pip \
  fontconfig locales >/dev/null
locale-gen zh_CN.UTF-8 en_US.UTF-8 2>/dev/null || true

# ---------------------------------------------------------------- 4. JDK 17
if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q 'version "17'; then
  log "JDK 17 已安装，跳过"
else
  log "安装 OpenJDK 17..."
  apt-get install -y -qq --no-install-recommends openjdk-17-jdk-headless >/dev/null
fi
java -version 2>&1 | head -1 || die "JDK 安装失败"
JAVA_BIN="$(command -v java)"
log "JAVA_HOME=$(dirname "$(dirname "$(readlink -f "${JAVA_BIN}")")")"

# ---------------------------------------------------------------- 5. Maven
if [[ -x /opt/maven/bin/mvn ]] && /opt/maven/bin/mvn -v 2>/dev/null | grep -q "3.9"; then
  log "Maven 3.9 已安装，跳过"
else
  log "安装 Maven ${MVN_VERSION}（官方二进制）..."
  cd /tmp
  MVN_TAR="apache-maven-${MVN_VERSION}-bin.tar.gz"
  if ! curl -fsSL -o "${MVN_TAR}" \
       "https://maven.aliyun.com/repository/public/org/apache/maven/apache-maven/${MVN_VERSION}/${MVN_TAR}" \
       2>/dev/null && [[ ! -s ${MVN_TAR} ]]; then
    warn "阿里云镜像下载失败，回退 Apache 官方源"
    curl -fsSL -o "${MVN_TAR}" \
      "https://archive.apache.org/dist/maven/maven-3/${MVN_VERSION}/binaries/${MVN_TAR}"
  fi
  tar -xzf "${MVN_TAR}" -C /opt
  ln -sfn "/opt/apache-maven-${MVN_VERSION}" /opt/maven
  rm -f "${MVN_TAR}"
fi
ln -sfn /opt/maven/bin/mvn /usr/local/bin/mvn
mvn -v 2>/dev/null | head -1 || die "Maven 安装失败"

# 阿里云镜像：换成中央仓库镜像，服务器在国内下载速度从几十 KB/s 提到数 MB/s
log "写入 Maven 阿里云镜像 ~/.m2/settings.xml"
mkdir -p /root/.m2
cat > /root/.m2/settings.xml <<'SETTINGS'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <mirrors>
    <mirror>
      <id>aliyun-public</id>
      <name>Aliyun Public Repository</name>
      <url>https://maven.aliyun.com/repository/public</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
  <profiles>
    <profile>
      <id>aliyun-repos</id>
      <repositories>
        <repository>
          <id>aliyun-spring</id>
          <url>https://maven.aliyun.com/repository/spring</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>false</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
</settings>
SETTINGS

# ---------------------------------------------------------------- 6. Node.js
install_node() {
  log "安装 Node.js ${NODE_VERSION}..."
  local tar="node-v${NODE_VERSION}-linux-x64.tar.xz"
  cd /tmp
  # 优先 npmmirror（国内快），失败回退官方
  curl -fsSL -o "${tar}" "https://npmmirror.com/mirrors/node/v${NODE_VERSION}/${tar}" \
    || curl -fsSL -o "${tar}" "https://nodejs.org/dist/v${NODE_VERSION}/${tar}"
  tar -xJf "${tar}" -C /opt
  ln -sfn "/opt/node-v${NODE_VERSION}-linux-x64" /opt/node
  ln -sfn /opt/node/bin/node /usr/local/bin/node
  ln -sfn /opt/node/bin/npm  /usr/local/bin/npm
  ln -sfn /opt/node/bin/npx  /usr/local/bin/npx
  rm -f "${tar}"
}
if [[ -x /opt/node/bin/node ]] && /opt/node/bin/node -v | grep -q "v22"; then
  log "Node 22 已安装，跳过"
else
  install_node
fi
node -v || die "Node 安装失败"
npm -v
npm config set registry https://registry.npmmirror.com --global
log "npm registry -> $(npm config get registry)"

# ---------------------------------------------------------------- 7. Swap（小内存救命）
# vite build + vue-tsc 在 1C2G 机器上极易 OOM，2GB swap 是最省事的兜底
if [[ -f /swapfile ]] && swapon --show | grep -q swapfile; then
  log "swap 已存在，跳过"
elif (( MEM_MB <= 3072 )); then
  log "内存 ${MEM_MB}MB 偏小，创建 2GB swap..."
  fallocate -l 2G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  sysctl -w vm.swappiness=10 >/dev/null || true
fi

# ---------------------------------------------------------------- 8. Docker（只跑 MySQL/Redis）
if command -v docker >/dev/null 2>&1; then
  log "Docker 已安装: $(docker -v | head -1)"
else
  log "安装 Docker..."
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL "https://mirrors.aliyun.com/docker-ce/linux/${ID}/gpg" \
    | gpg --dearmor -o /etc/apt/keyrings/docker.gpg 2>/dev/null \
    || curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
       | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/${ID} ${UBUNTU_CODENAME:-${VERSION_CODENAME}} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get install -y -qq --no-install-recommends docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin >/dev/null
  systemctl enable --now docker >/dev/null
fi
docker compose version >/dev/null 2>&1 || die "docker compose 插件不可用"

# Docker 镜像加速（阿里云个人加速器，拉取 mysql/redis 用）
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'DAEMON'
{
  "registry-mirrors": [
    "https://registry.cn-hangzhou.aliyuncs.com",
    "https://docker.mirrors.ustc.edu.cn"
  ],
  "log-driver": "json-file",
  "log-opts": {"max-size": "50m", "max-file": "3"}
}
DAEMON
if systemctl is-active --quiet docker; then
  systemctl restart docker
fi
# 等 Docker 真正就绪，否则下一步 docker compose up 会失败
for i in {1..30}; do
  # 必须写成 if：set -e 下 `cmd && break` 在 cmd 失败时会直接终止脚本
  if docker info >/dev/null 2>&1; then break; fi
  sleep 2
done
docker info >/dev/null 2>&1 || die "Docker 守护进程未就绪"

# ---------------------------------------------------------------- 9. Nginx
if command -v nginx >/dev/null 2>&1; then
  log "Nginx 已安装: $(nginx -v 2>&1 | head -1)"
else
  log "安装 Nginx..."
  apt-get install -y -qq --no-install-recommends nginx >/dev/null
  systemctl enable nginx >/dev/null
fi

# ---------------------------------------------------------------- 10. 防火墙
if command -v ufw >/dev/null 2>&1; then
  ufw allow 22/tcp  >/dev/null 2>&1 || true
  ufw allow 80/tcp  >/dev/null 2>&1 || true
  ufw allow 443/tcp >/dev/null 2>&1 || true
  ufw --force enable >/dev/null 2>&1 || true
  log "ufw 已放行 22/80/443"
fi

# ---------------------------------------------------------------- 11. 时区
timedatectl set-timezone Asia/Shanghai 2>/dev/null || true
log "时区: $(date +'%Z %z')"

# ---------------------------------------------------------------- 结果汇总
cat <<EOF

$(printf '\033[0;32m[prepare]\033[0m') 环境准备完成
  Java    : $(java -version 2>&1 | head -1)
  Maven   : $(mvn -v 2>/dev/null | head -1)
  Node    : $(node -v) (npm $(npm -v))
  Nginx   : $(nginx -v 2>&1 | head -1)
  Docker  : $(docker -v | head -1)
  目录     : ${PDK_ROOT}
  Swap    : $(swapon --show | tail -n +2 | awk '{print $3}' || echo 'none')

$(printf '\033[0;33m[prepare]\033[0m') 重要提醒：还需到【阿里云控制台 → 安全组】放行 80 端口（22 默认已开），
  否则外网访问不了 http://$(curl -s --max-time 3 ifconfig.me 2>/dev/null || echo '<服务器公网IP>')

下一步：bash ${PDK_ROOT}/scripts/02-init-infra.sh
EOF
