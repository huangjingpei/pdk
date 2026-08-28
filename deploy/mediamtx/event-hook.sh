#!/bin/sh
set -eu

event="${1:-}"
case "$event" in
  available|unavailable) ;;
  *) exit 2 ;;
esac

: "${PDK_MEDIAMTX_EVENT_BASE_URL:?PDK_MEDIAMTX_EVENT_BASE_URL is required}"
: "${PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN:?PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN is required}"

# MTX_QUERY 可能包含明文 publish ticket，禁止传给业务服务器或写入日志。
curl --fail --silent --show-error --max-time 5 \
  --request POST "${PDK_MEDIAMTX_EVENT_BASE_URL}/${event}" \
  --data-urlencode "serviceToken=${PDK_MEDIAMTX_INTERNAL_SERVICE_TOKEN}" \
  --data-urlencode "path=${MTX_PATH:-}" \
  --data-urlencode "sourceId=${MTX_SOURCE_ID:-}"
