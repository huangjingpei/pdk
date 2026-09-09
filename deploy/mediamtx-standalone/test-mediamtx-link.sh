#!/usr/bin/env bash
# ============================================================================
# MediaMTX 与业务后端双向网络连通性及鉴权验证脚本
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "${SCRIPT_DIR}/.env" ]]; then
  # shellcheck disable=SC1091
  set -a; . "${SCRIPT_DIR}/.env"; set +a
fi

BACKEND_URL="${PDK_BACKEND_PUBLIC_BASE_URL:-https://pdk.graddu.com}"
TOKEN="${PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN:-}"
NODE="${PDK_MEDIAMTX_NODE_CODE:-mediamtx-remote-1}"

echo "========================================================"
echo " 正在测试 MediaMTX -> 业务后端 (https://pdk.graddu.com) 连通性"
echo "========================================================"

echo -n "1. 测试后端 HTTPS 基础连通性: "
if curl -k -fsS --max-time 5 "${BACKEND_URL}/" >/dev/null 2>&1 || curl -k -I --max-time 5 "${BACKEND_URL}/" >/dev/null 2>&1; then
  echo "✅ 正常通畅"
else
  echo "❌ 失败，无法连接到 ${BACKEND_URL}"
fi

echo -n "2. 测试 MediaMTX 内部鉴权接口响应: "
AUTH_RESP=$(curl -k -s -o /dev/null -w "%{http_code}" --max-time 5 \
  "${BACKEND_URL}/api/v1/internal/mediamtx/auth?serviceToken=${TOKEN}&nodeCode=${NODE}" || echo "ERR")

if [[ "${AUTH_RESP}" == "401" || "${AUTH_RESP}" == "403" || "${AUTH_RESP}" == "204" ]]; then
  echo "✅ 接口畅通 (HTTP ${AUTH_RESP})，业务后端成功响应鉴权请求"
elif [[ "${AUTH_RESP}" == "ERR" || "${AUTH_RESP}" == "000" ]]; then
  echo "❌ 网络超时或无法访问接口"
else
  echo "⚠️ 接口返回 HTTP ${AUTH_RESP}"
fi

echo -n "3. 测试本地 MediaMTX 端口 1935 (RTMP): "
if ss -tln 2>/dev/null | grep -q ':1935 ' || netstat -tln 2>/dev/null | grep -q ':1935 '; then
  echo "✅ 1935 端口已在监听"
else
  echo "⚠️ 1935 端口未在监听，请确认 mediamtx 服务是否已启动"
fi

echo -n "4. 测试本地 MediaMTX Control API (9997): "
if curl -fsS --max-time 3 "http://127.0.0.1:9997/v3/paths/list" >/dev/null 2>&1; then
  echo "✅ 9997 API 响应正常"
else
  echo "⚠️ 9997 API 暂未响应，请检查 mediamtx.yml 中的 api: yes 与 apiAddress: :9997"
fi

echo "========================================================"
echo " 连通性测试结束"
echo "========================================================"
