#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Replaces the OWNER placeholder with your Docker Hub user / GitHub owner, everywhere.
#   scripts/set-namespace.sh <docker-hub-user-or-github-owner> ["Copyright holder"]
set -euo pipefail

OWNER="${1:-}"
HOLDER="${2:-}"

if [ -z "$OWNER" ]; then
  echo "usage: scripts/set-namespace.sh <owner> [\"Copyright holder\"]" >&2
  echo "  <owner>           your Docker Hub username / GitHub owner, e.g. janedoe" >&2
  echo "  Copyright holder  name for the MIT licence (default: the owner)" >&2
  exit 1
fi
[ -z "$HOLDER" ] && HOLDER="$OWNER"

files=(README.md CHANGELOG.md server.json Dockerfile docker-compose.yml .env.example action.yml
        docs/docker-mcp-catalog-entry.yaml docs/RELEASING.md docs/dockerhub-overview.md)

for file in "${files[@]}"; do
  [ -f "$file" ] || continue
  sed -i "s/OWNER/$OWNER/g" "$file"
  echo "updated $file"
done

# The compose default and README examples point at the local build tag until then.
sed -i "s#mobsf-mcp:local#$OWNER/mobsf-mcp:latest#g" README.md docker-compose.yml .env.example 2>/dev/null || true

sed -i "s/Copyright (c) 2026 mobsf-mcp contributors/Copyright (c) 2026 $HOLDER/" LICENSE
echo "updated LICENSE (copyright: $HOLDER)"

echo
echo "Done. Review the changes, then see docs/RELEASING.md."
grep -rn "OWNER" --include="*.md" --include="*.json" --include="*.yaml" --include="*.yml" . \
  --exclude-dir=target --exclude-dir=.git 2>/dev/null | grep -v set-namespace \
  && echo "^ these still mention OWNER; check them by hand" || echo "No OWNER placeholders left."
