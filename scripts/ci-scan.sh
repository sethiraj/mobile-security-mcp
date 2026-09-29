#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Scans an app through a running mobsf-mcp server and fails when findings exceed a
# threshold. Used by the GitHub Action in action.yml, and usable on its own.
#
#   scripts/ci-scan.sh <app-file>
#
# Environment:
#   MCP_URL          MCP endpoint            (default http://localhost:8080/mcp)
#   MCP_AUTH_TOKEN   bearer token            (read from .env when present)
#   MAX_HIGH         allowed high findings   (default 0; -1 disables the gate)
#   MIN_SCORE        minimum security score  (default 0 = no minimum)
#   OUT_DIR          where reports are written on the host (default ./reports)
#   SARIF            true to also export SARIF (default true)
set -euo pipefail

APP_PATH="${1:-}"
MCP_URL="${MCP_URL:-http://localhost:8080/mcp}"
TOKEN="${MCP_AUTH_TOKEN:-$(grep -E '^MCP_AUTH_TOKEN=' .env 2>/dev/null | cut -d= -f2- || true)}"
MAX_HIGH="${MAX_HIGH:-0}"
MIN_SCORE="${MIN_SCORE:-0}"
OUT_DIR="${OUT_DIR:-./reports}"
SARIF="${SARIF:-true}"
SESSION=""

if [ -z "$APP_PATH" ]; then
  echo "usage: scripts/ci-scan.sh <app-file>   (the file must be inside the server's upload directory)" >&2
  exit 2
fi
APP_NAME="$(basename "$APP_PATH")"

rpc() { # rpc <method> <params-json> [timeout]
  local method="$1" params="${2:-{\}}" timeout="${3:-120}"
  local headers=(-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream")
  [ -n "$TOKEN" ] && headers+=(-H "Authorization: Bearer $TOKEN")
  [ -n "$SESSION" ] && headers+=(-H "Mcp-Session-Id: $SESSION")
  curl -sS --no-buffer -m "$timeout" "${headers[@]}" \
      -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\",\"params\":$params}" "$MCP_URL" 2>/dev/null \
    | sed -n -e 's/^data: \{0,1\}//p' -e '/^{/p' | head -1
}

value() { { printf '%s' "$1" | grep -o "\\\\\"$2\\\\\":[^,}]*" || true; } | head -1 | sed 's/.*://; s/\\\"//g; s/"//g'; }

fail() { echo "::error::$*" 2>/dev/null || true; echo "FAILED: $*" >&2; exit 1; }

SESSION=$(curl -sS -m 30 -D - -o /dev/null \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  ${TOKEN:+-H "Authorization: Bearer $TOKEN"} \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"ci-scan","version":"1"}}}' \
  "$MCP_URL" | grep -i '^mcp-session-id:' | cut -d' ' -f2 | tr -d '\r')
[ -n "$SESSION" ] || fail "could not start an MCP session at $MCP_URL (is the server running, and is MCP_AUTH_TOKEN right?)"
rpc "notifications/initialized" "{}" 10 > /dev/null 2>&1 || true

health=$(rpc "tools/call" '{"name":"mobsf_health","arguments":{}}')
printf '%s' "$health" | grep -q '\\"ok\\":true' || fail "MobSF is not reachable: $(printf '%s' "$health" | cut -c1-400)"

echo "Uploading $APP_NAME"
upload=$(rpc "tools/call" "{\"name\":\"upload_app\",\"arguments\":{\"path\":\"$APP_PATH\"}}" 300)
HASH=$(value "$upload" hash)
[ -n "$HASH" ] || fail "upload failed: $(printf '%s' "$upload" | cut -c1-400)"

echo "Scanning (hash $HASH)"
scan=$(rpc "tools/call" "{\"name\":\"scan_app\",\"arguments\":{\"hash\":\"$HASH\",\"waitSeconds\":1800}}" 1860)
printf '%s' "$scan" | grep -q '\\"status\\":\\"completed\\"' || fail "scan did not complete: $(printf '%s' "$scan" | cut -c1-400)"

card=$(rpc "tools/call" "{\"name\":\"get_scorecard\",\"arguments\":{\"hash\":\"$HASH\"}}")
SCORE=$(value "$card" securityScore)
HIGH=$(printf '%s' "$card" | grep -o '\\"high\\":[0-9]*' | head -1 | grep -o '[0-9]*$' || echo 0)
HIGH="${HIGH:-0}"

rpc "tools/call" "{\"name\":\"export_report\",\"arguments\":{\"hash\":\"$HASH\"}}" 180 > /dev/null
if [ "$SARIF" = "true" ]; then
  rpc "tools/call" "{\"name\":\"export_report\",\"arguments\":{\"hash\":\"$HASH\",\"format\":\"sarif\"}}" 180 > /dev/null
fi

echo
echo "app:            $APP_NAME"
echo "hash:           $HASH"
echo "security score: ${SCORE:-unknown}/100"
echo "high findings:  $HIGH"
echo "reports:        $OUT_DIR/$HASH/"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "hash=$HASH"
    echo "score=${SCORE:-}"
    echo "high=$HIGH"
    echo "report=$OUT_DIR/$HASH/static.json"
    echo "sarif=$OUT_DIR/$HASH/static.sarif.json"
  } >> "$GITHUB_OUTPUT"
fi

if [ "$MAX_HIGH" != "-1" ] && [ "$HIGH" -gt "$MAX_HIGH" ]; then
  fail "$HIGH high-severity findings exceed the allowed maximum of $MAX_HIGH"
fi
if [ "$MIN_SCORE" -gt 0 ] && [ -n "${SCORE:-}" ] && [ "$SCORE" -lt "$MIN_SCORE" ]; then
  fail "security score $SCORE is below the required minimum of $MIN_SCORE"
fi
echo "PASS"
