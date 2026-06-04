#!/usr/bin/env bash
#
# API smoke test for the SQL Explorer backend.
#
# Exercises the endpoints we verify by hand after a dependency/framework
# upgrade: health, the main read APIs, the tag write path (JPA/Hibernate),
# multipart upload (LobCreator), swagger/openapi, and the prometheus
# actuator endpoint. Creates a tag and uploads a source, then cleans the
# tag up; the uploaded source has no delete endpoint and is left behind
# (deduped by md5 on re-runs).
#
# Everything is served under the servlet context-path (see
# server.servlet.context-path in application.yml), so the API, actuator,
# and docs all live beneath $BASE_URL$CONTEXT_PATH.
#
# Usage:
#   ./smoke-test.sh                 # against http://localhost:18180 (docker)
#   BASE_URL=http://localhost:8080 ./smoke-test.sh   # against a local run
#   CONTEXT_PATH=/ ./smoke-test.sh  # if the context-path is ever removed
#
# Exits non-zero if any check fails.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:18180}"
CONTEXT_PATH="${CONTEXT_PATH:-/api/sql-explorer}"
ROOT="${BASE_URL%/}${CONTEXT_PATH%/}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-120}"  # seconds to wait for health

pass=0
fail=0

# check <name> <expected-http> <curl-args...>
# Runs curl, compares the HTTP status, prints PASS/FAIL. Response body is
# written to $BODY for callers that want to inspect it.
BODY=""
check() {
  local name="$1" expected="$2"; shift 2
  local tmp code
  tmp="$(mktemp)"
  code="$(curl -s -o "$tmp" -w '%{http_code}' "$@")"
  BODY="$(cat "$tmp")"
  rm -f "$tmp"
  if [ "$code" = "$expected" ]; then
    printf '  \033[32mPASS\033[0m  %-40s %s\n' "$name" "$code"
    pass=$((pass + 1))
  else
    printf '  \033[31mFAIL\033[0m  %-40s got %s, expected %s\n' "$name" "$code" "$expected"
    printf '        %s\n' "$(echo "$BODY" | head -c 200)"
    fail=$((fail + 1))
  fi
}

wait_for_health() {
  echo "Waiting for $ROOT to be healthy (up to ${HEALTH_TIMEOUT}s)..."
  local waited=0
  while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
    if curl -sf "$ROOT/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "Healthy after ~${waited}s"
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  echo "ERROR: backend never became healthy" >&2
  return 1
}

wait_for_health || exit 1

echo
echo "Read endpoints"
check "schemas"                200 "$ROOT/schemas"
check "tables"                 200 "$ROOT/tables"
check "sources"                200 "$ROOT/sources"
check "sources/active"         200 "$ROOT/sources/active"
check "user"                   200 "$ROOT/user"
check "graph/all"              200 "$ROOT/graph/all"
check "report (slow)"          200 "$ROOT/analyzed-sql/reports/slow?limit=2"
check "report (default limit)" 200 "$ROOT/analyzed-sql/reports/slow"
check "tags/names"             200 "$ROOT/tags/names?value=smoke"

echo
echo "Tag write path (JPA / Hibernate, IDENTITY id generation)"
TAG_PATH="/smoke/$(date +%s)"
check "create tag"             200 -X POST -H 'Content-Type: application/json' \
  -d "{\"name\":\"smoke-test\",\"path\":\"$TAG_PATH\"}" "$ROOT/tags"
TAG_ID="$(echo "$BODY" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)"
check "read tag back"          200 "$ROOT/tags/path?path=$TAG_PATH"
if [ -n "$TAG_ID" ]; then
  check "delete tag"           200 -X DELETE "$ROOT/tag/$TAG_ID"
else
  printf '  \033[31mFAIL\033[0m  %-40s could not parse tag id\n' "delete tag"
  fail=$((fail + 1))
fi

echo
echo "Multipart upload (LobCreator blob path, md5 from stream)"
UPLOAD_CSV="$(mktemp /tmp/smoke-XXXX.csv)"
printf 'id,sql\n' > "$UPLOAD_CSV"
check "upload source"          201 -X POST -F "sourceRef=smoke-test" -F "file=@$UPLOAD_CSV" "$ROOT/sources"
rm -f "$UPLOAD_CSV"

echo
echo "Docs and actuator"
check "v3/api-docs"            200 "$ROOT/v3/api-docs"
check "swagger-ui"             200 "$ROOT/swagger-ui/index.html"
check "scalar"                 200 "$ROOT/scalar"
check "actuator/health"        200 "$ROOT/actuator/health"
check "actuator/prometheus"    200 "$ROOT/actuator/prometheus"

echo
echo "----------------------------------------"
echo "Passed: $pass   Failed: $fail"
if [ "$fail" -gt 0 ]; then
  exit 1
fi
