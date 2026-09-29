#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# End-to-end check of the compose stack: MCP over Streamable HTTP -> MobSF -> a real scan.
# Usage: scripts/smoke-test.sh [path-to-apk-in-./apks]   (env: MCP_URL, MCP_AUTH_TOKEN)
set -euo pipefail

MCP_URL="${MCP_URL:-http://localhost:8080/mcp}"
TOKEN="${MCP_AUTH_TOKEN:-$(grep -E '^MCP_AUTH_TOKEN=' .env 2>/dev/null | cut -d= -f2- || true)}"
APK_NAME="${1:-$(ls apks/*.apk 2>/dev/null | head -1 | xargs -r basename)}"
SESSION=""

if [ -z "$APK_NAME" ]; then
  echo "No APK found. Put one in ./apks (e.g. DIVA or InsecureBankv2) and re-run." >&2
  exit 1
fi

rpc() { # rpc <method> <params-json> [timeout-seconds] ; prints the JSON-RPC response
  local method="$1" params="${2:-{\}}" timeout="${3:-60}"
  local headers=(-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream")
  [ -n "$TOKEN" ] && headers+=(-H "Authorization: Bearer $TOKEN")
  [ -n "$SESSION" ] && headers+=(-H "Mcp-Session-Id: $SESSION")
  # The server answers on an SSE stream it keeps open, so stop reading at the first
  # message: head closes the pipe and curl exits instead of waiting for the timeout.
  curl -sS --no-buffer -m "$timeout" "${headers[@]}" \
      -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\",\"params\":$params}" "$MCP_URL" 2>/dev/null \
    | sed -n -e 's/^data: \{0,1\}//p' -e '/^{/p' | head -1
}

value() { # value <json> <key> -> first match, strings or numbers
  { printf '%s' "$1" | grep -o "\\\\\"$2\\\\\":[^,}]*" || true; } | head -1 | sed 's/.*://; s/\\\"//g; s/"//g'
}

echo "1/7 initialize"
init_headers=$(curl -sS -m 30 -D - -o /tmp/mcp-init.json \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  ${TOKEN:+-H "Authorization: Bearer $TOKEN"} \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1"}}}' \
  "$MCP_URL")
SESSION=$(printf '%s' "$init_headers" | grep -i '^mcp-session-id:' | cut -d' ' -f2 | tr -d '\r')
grep -q '"serverInfo"' /tmp/mcp-init.json || { echo "initialize failed:"; cat /tmp/mcp-init.json; exit 1; }
rpc "notifications/initialized" "{}" 10 > /dev/null 2>&1 || true

echo "2/7 tools/list"
tools=$(rpc "tools/list")
for tool in mobsf_health upload_app scan_app get_scorecard get_report; do
  printf '%s' "$tools" | grep -q "\"$tool\"" || { echo "missing tool: $tool"; exit 1; }
done

echo "3/7 mobsf_health"
health=$(rpc "tools/call" '{"name":"mobsf_health","arguments":{}}')
printf '%s' "$health" | grep -q '\\"ok\\":true' || { echo "MobSF not healthy: $health"; exit 1; }

echo "4/7 upload_app ($APK_NAME)"
upload=$(rpc "tools/call" "{\"name\":\"upload_app\",\"arguments\":{\"path\":\"/data/apks/$APK_NAME\"}}" 300)
HASH=$(value "$upload" hash)
[ -n "$HASH" ] || { echo "upload failed: $upload"; exit 1; }
echo "    hash=$HASH"

echo "5/7 scan_app (this can take a few minutes)"
scan=$(rpc "tools/call" "{\"name\":\"scan_app\",\"arguments\":{\"hash\":\"$HASH\",\"waitSeconds\":900}}" 960)
printf '%s' "$scan" | grep -q '\\"status\\":\\"completed\\"' || { echo "scan did not complete: $scan"; exit 1; }

echo "6/7 get_scorecard"
card=$(rpc "tools/call" "{\"name\":\"get_scorecard\",\"arguments\":{\"hash\":\"$HASH\"}}")
SCORE=$(value "$card" securityScore)
TRACKERS=$(value "$card" trackersDetected)

echo "7/7 export_report + schema validation"
export_out=$(rpc "tools/call" "{\"name\":\"export_report\",\"arguments\":{\"hash\":\"$HASH\"}}" 120)
printf '%s' "$export_out" | grep -q 'mobsf-mcp-report/v1' || { echo "export failed: $export_out"; exit 1; }
REPORT="reports/$HASH/static.json"
if command -v npx > /dev/null 2>&1; then
  npx --yes -p ajv-cli@5 -p ajv-formats@2 ajv validate -c ajv-formats \
    -s schemas/mobsf-mcp-report.v1.json -d "$REPORT" --spec=draft2020 > /dev/null 2>&1 \
    && echo "      $REPORT matches schemas/mobsf-mcp-report.v1.json" \
    || { echo "report does not match the published schema"; exit 1; }
else
  echo "      (npx not available; skipped schema validation)"
fi

echo
echo "PASS: $APK_NAME scored $SCORE/100, trackers found: $TRACKERS, hash $HASH"
echo "      normalized report: $REPORT"
echo "      MobSF UI: http://localhost:8000/recent_scans/"
