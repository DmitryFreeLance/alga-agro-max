#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.server}"
DB_PATH="${DB_PATH:-/opt/alga-agro/data/alga-agro.db}"
CATALOG_SQL="${CATALOG_SQL:-$ROOT_DIR/server_catalog_sync.sql}"
IMAGE_NAME="${IMAGE_NAME:-alga-agro-max}"
CONTAINER_NAME="${CONTAINER_NAME:-alga-agro-max}"
if [[ ! -f "$CATALOG_SQL" ]]; then
  echo "Missing catalog SQL: $CATALOG_SQL"
  exit 1
fi

if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
fi

TZ="${TZ:-Europe/Moscow}"
APP_HOST_PORT="${APP_HOST_PORT:-8080}"
APP_PUBLIC_BASE_URL="${APP_PUBLIC_BASE_URL:-https://algaagro.ru}"
APP_MINI_APP_URL="${APP_MINI_APP_URL:-https://algaagro.ru/miniapp/}"
APP_MANAGER_CONTACT_URL="${APP_MANAGER_CONTACT_URL:-https://max.ru/id27849376}"
APP_MANAGER_DEEP_LINK="${APP_MANAGER_DEEP_LINK:-max://user/27849376}"
MAX_BOT_TOKEN="${MAX_BOT_TOKEN:-f9LHodD0cOK5XUlcul2XBZTUFB63NBsr_GlAkrYbQxkgnJR0p1-ApwRZX7mU0sHDF0BdSouySLId5IJSLK3N}"
APP_STARTUP_ADMIN_USER_IDS="${APP_STARTUP_ADMIN_USER_IDS:-188421258}"
MAX_POST_TARGET_CHAT_ID="${MAX_POST_TARGET_CHAT_ID:--71054450974880}"
KIE_API_KEY="${KIE_API_KEY:-48e07ca7a9144b5507abb8cb0af45bdc}"
KIE_GEMINI_API_KEY="${KIE_GEMINI_API_KEY:-48e07ca7a9144b5507abb8cb0af45bdc}"
KIE_GEMINI_MODEL="${KIE_GEMINI_MODEL:-gemini-3-flash}"
KIE_GEMINI_ENDPOINT="${KIE_GEMINI_ENDPOINT:-/gemini-3-flash/v1/chat/completions}"
KIE_MODEL="${KIE_MODEL:-gpt-5-2}"
BITRIX_WEBHOOK_BASE_URL="${BITRIX_WEBHOOK_BASE_URL:-https://b24-1wriw9.bitrix24.ru/rest/16/l1xvvuoa1n1tmz3w}"
BITRIX_SYNC_ENABLED="${BITRIX_SYNC_ENABLED:-true}"
BITRIX_INBOUND_SYNC_ENABLED="${BITRIX_INBOUND_SYNC_ENABLED:-false}"
BITRIX_STARTUP_REPLACE_REMOTE_CATALOG="${BITRIX_STARTUP_REPLACE_REMOTE_CATALOG:-true}"
BITRIX_CURRENCY_ID="${BITRIX_CURRENCY_ID:-RUB}"
BITRIX_POLL_INTERVAL_MS="${BITRIX_POLL_INTERVAL_MS:-180000}"
BITRIX_LEAD_ASSIGNED_BY_ID="${BITRIX_LEAD_ASSIGNED_BY_ID:-16}"

mkdir -p "$(dirname "$DB_PATH")"

echo "Building Docker image from $ROOT_DIR ..."
docker build -t "$IMAGE_NAME" "$ROOT_DIR"

echo "Recreating container $CONTAINER_NAME ..."
docker stop "$CONTAINER_NAME" >/dev/null 2>&1 || true
docker rm "$CONTAINER_NAME" >/dev/null 2>&1 || true

docker run -d \
  --name "$CONTAINER_NAME" \
  --restart unless-stopped \
  -p "${APP_HOST_PORT}:8080" \
  -v /opt/alga-agro/data:/data \
  -e TZ="${TZ}" \
  -e SERVER_PORT=8080 \
  -e APP_DB_PATH=/data/alga-agro.db \
  -e APP_PUBLIC_BASE_URL="${APP_PUBLIC_BASE_URL}" \
  -e APP_MINI_APP_URL="${APP_MINI_APP_URL}" \
  -e APP_MANAGER_CONTACT_URL="${APP_MANAGER_CONTACT_URL}" \
  -e APP_MANAGER_DEEP_LINK="${APP_MANAGER_DEEP_LINK}" \
  -e MAX_BOT_TOKEN="${MAX_BOT_TOKEN}" \
  -e APP_STARTUP_ADMIN_USER_IDS="${APP_STARTUP_ADMIN_USER_IDS}" \
  -e MAX_POST_TARGET_CHAT_ID="${MAX_POST_TARGET_CHAT_ID}" \
  -e KIE_API_KEY="${KIE_API_KEY}" \
  -e KIE_GEMINI_API_KEY="${KIE_GEMINI_API_KEY}" \
  -e KIE_GEMINI_MODEL="${KIE_GEMINI_MODEL}" \
  -e KIE_GEMINI_ENDPOINT="${KIE_GEMINI_ENDPOINT}" \
  -e KIE_MODEL="${KIE_MODEL}" \
  -e BITRIX_WEBHOOK_BASE_URL="${BITRIX_WEBHOOK_BASE_URL}" \
  -e BITRIX_SYNC_ENABLED="${BITRIX_SYNC_ENABLED}" \
  -e BITRIX_INBOUND_SYNC_ENABLED="${BITRIX_INBOUND_SYNC_ENABLED}" \
  -e BITRIX_STARTUP_REPLACE_REMOTE_CATALOG="${BITRIX_STARTUP_REPLACE_REMOTE_CATALOG}" \
  -e BITRIX_CURRENCY_ID="${BITRIX_CURRENCY_ID}" \
  -e BITRIX_POLL_INTERVAL_MS="${BITRIX_POLL_INTERVAL_MS}" \
  -e BITRIX_LEAD_ASSIGNED_BY_ID="${BITRIX_LEAD_ASSIGNED_BY_ID}" \
  "$IMAGE_NAME"

echo "Waiting for schema bootstrap ..."
schema_ready=0
for _ in $(seq 1 30); do
  if [[ -f "$DB_PATH" ]] && sqlite3 "$DB_PATH" ".tables" | grep -q "catalog_products"; then
    schema_ready=1
    break
  fi
  sleep 2
done

if [[ "$schema_ready" -ne 1 ]]; then
  echo "Schema bootstrap did not finish in time."
  docker logs --tail 120 "$CONTAINER_NAME" || true
  exit 1
fi

echo "Syncing catalog from $CATALOG_SQL ..."
sqlite3 "$DB_PATH" < "$CATALOG_SQL"

echo "Restarting container after catalog sync ..."
docker restart "$CONTAINER_NAME" >/dev/null

echo
echo "Container status:"
docker ps --filter "name=$CONTAINER_NAME"

echo
echo "Catalog snapshot:"
sqlite3 "$DB_PATH" "select count(*) as products from catalog_products; select category, count(*) from catalog_products group by category order by count(*) desc;"

echo
echo "Recent logs:"
docker logs --tail 120 "$CONTAINER_NAME" || true
