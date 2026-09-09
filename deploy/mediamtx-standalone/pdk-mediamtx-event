#!/bin/sh
# ============================================================================
# MediaMTX 事件钩子 (生命周期通知业务系统)
# 部署至 MediaMTX 宿主机的 /usr/local/bin/pdk-mediamtx-event
# ============================================================================
set -eu

event="${1:-}"
case "$event" in
  available|unavailable|read|unread) ;;
  *) exit 2 ;;
esac

PDK_MEDIAMTX_EVENT_BASE_URL="${PDK_MEDIAMTX_EVENT_BASE_URL:-https://pdk.graddu.com/api/v1/internal/mediamtx/events}"
PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN="${PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN:-9f1ec0172d87a106fd26e031e6f79c2e5350bc004d5c9dd485bf2857026b4d3d}"
PDK_MEDIAMTX_NODE_CODE="${PDK_MEDIAMTX_NODE_CODE:-mediamtx-remote-1}"

# 注意：MTX_QUERY 可能包含推流票据明文，严格禁止传给非鉴权接口或落盘
curl --fail --silent --show-error --max-time 5 \
  --request POST "${PDK_MEDIAMTX_EVENT_BASE_URL}/${event}" \
  --data-urlencode "serviceToken=${PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN}" \
  --data-urlencode "nodeCode=${PDK_MEDIAMTX_NODE_CODE}" \
  --data-urlencode "path=${MTX_PATH:-}" \
  --data-urlencode "sourceId=${MTX_SOURCE_ID:-}" \
  --data-urlencode "readerId=${MTX_READER_ID:-}" \
  --data-urlencode "readerType=${MTX_READER_TYPE:-}" \
  --data-urlencode "clientIp=${MTX_READER_IP:-}"
