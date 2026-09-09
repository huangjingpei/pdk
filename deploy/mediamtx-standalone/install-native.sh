#!/usr/bin/env bash
# ============================================================================
# MediaMTX 原生二进制一键安装与 systemd 服务配置脚本 (Linux x86_64)
# 在 43.248.187.207 上以 root 运行: bash install-native.sh
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

if [[ ! -f ".env" ]]; then
  if [[ -f "env.example" ]]; then
    echo "未找到 .env，已从 env.example 复制。请先编辑 .env 填写 PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN 后再执行本脚本！"
    cp env.example .env
    exit 1
  else
    echo "缺少 .env 配置文件" >&2
    exit 1
  fi
fi

# shellcheck disable=SC1091
set -a; . ./.env; set +a

[[ -n "${PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN:-}" ]] || { echo "请在 .env 中设置 PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN" >&2; exit 1; }

PDK_BACKEND_PUBLIC_BASE_URL="${PDK_BACKEND_PUBLIC_BASE_URL:-https://pdk.graddu.com}"
PDK_BACKEND_SERVER_IP="${PDK_BACKEND_SERVER_IP:-121.43.150.109}"
PDK_MEDIAMTX_NODE_CODE="${PDK_MEDIAMTX_NODE_CODE:-mediamtx-remote-1}"

echo "=== 1. 安装系统依赖 (curl, tar) ==="
if command -v apt-get >/dev/null 2>&1; then
  apt-get update -y && apt-get install -y curl tar ca-certificates
elif command -v yum >/dev/null 2>&1; then
  yum install -y curl tar ca-certificates
fi

echo "=== 2. 下载 MediaMTX v1.11.3 官方二进制 ==="
TARGET_DIR="/opt/mediamtx"
mkdir -p "${TARGET_DIR}"
if [[ ! -f "${TARGET_DIR}/mediamtx" ]]; then
  curl -L -o /tmp/mediamtx.tar.gz "https://github.com/bluenviron/mediamtx/releases/download/v1.11.3/mediamtx_v1.11.3_linux_amd64.tar.gz"
  tar -xzf /tmp/mediamtx.tar.gz -C "${TARGET_DIR}"
  rm -f /tmp/mediamtx.tar.gz
  chmod +x "${TARGET_DIR}/mediamtx"
fi

echo "=== 3. 渲染并部署配置文件 ==="
sed \
  -e "s|__PDK_BACKEND_BASE_URL__|${PDK_BACKEND_PUBLIC_BASE_URL}|g" \
  -e "s|__PDK_BACKEND_SERVER_IP__|${PDK_BACKEND_SERVER_IP}|g" \
  -e "s|__PDK_MEDIAMTX_NODE_CODE__|${PDK_MEDIAMTX_NODE_CODE}|g" \
  -e "s|__PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN__|${PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN}|g" \
  "${SCRIPT_DIR}/mediamtx.yml" > "${TARGET_DIR}/mediamtx.yml"

echo "=== 4. 安装事件钩子 /usr/local/bin/pdk-mediamtx-event ==="
install -m 755 "${SCRIPT_DIR}/event-hook.sh" /usr/local/bin/pdk-mediamtx-event

echo "=== 5. 配置 systemd 服务 (mediamtx.service) ==="
cat > /etc/systemd/system/mediamtx.service <<EOF
[Unit]
Description=MediaMTX Real-time Streaming Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=${TARGET_DIR}
EnvironmentFile=${SCRIPT_DIR}/.env
Environment=PDK_MEDIAMTX_EVENT_BASE_URL=${PDK_BACKEND_PUBLIC_BASE_URL}/api/v1/internal/mediamtx/events
Environment=PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN=${PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN}
Environment=PDK_MEDIAMTX_NODE_CODE=${PDK_MEDIAMTX_NODE_CODE}
ExecStart=${TARGET_DIR}/mediamtx ${TARGET_DIR}/mediamtx.yml
Restart=always
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable mediamtx
systemctl restart mediamtx

echo "=== 6. 检查服务运行状态 ==="
sleep 2
systemctl status mediamtx --no-pager || true

echo "=== 部署完成！==="
echo "RTMP 推流端口 : 1935"
echo "HLS 播放端口  : 8888"
echo "API 控制端口  : 9997 (仅允许 ${PDK_BACKEND_SERVER_IP} 访问)"
