#!/usr/bin/env bash
# Test NDJSON append endpoint for ObjectStorage
# Validates: create file, append NDJSON (expect 200), stream back, list, delete
set -euo pipefail

BASE="http://localhost:8080"
API="/api/v1/files"
TENANT="test-tenant"
USER="test-user"
API_KEY="test-api-key"
FILE_NAME="test-events-$(date +%Y%m%d-%H%M%S).ndjson"
OBJECT_ID=""

H=(-H "x-tenant-id: $TENANT" -H "x-user-id: $USER" -H "x-api-key: $API_KEY")
pass=0 fail=0

ok() {
  echo "  PASS  $1"
  pass=$((pass + 1))
}
ko() {
  echo "  FAIL  $1"
  fail=$((fail + 1))
}
check() { [[ "$2" == "$3" ]] && ok "$1 (HTTP $2)" || ko "$1 (expected $3, got $2)"; }

echo "=== NDJSON Append Test ==="
echo ""

# 1. Create NDJSON file
echo "1. Create NDJSON file"
resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE$API" \
  "${H[@]}" -H "x-file-name: $FILE_NAME" -H "x-mimetype: application/x-ndjson" \
  --data-binary $'{"_init":true}\n')
code=$(echo "$resp" | tail -1)
body=$(echo "$resp" | sed '$d')
check "create file" "$code" "201"

OBJECT_ID=$(echo "$body" | grep -o '"objectId":"[^"]*"' | head -1 | cut -d'"' -f4)
[[ -n "$OBJECT_ID" ]] && ok "objectId=$OBJECT_ID" || { ko "no objectId"; exit 1; }
echo ""

# 2. Append NDJSON batch 1 (2 events) — must return 200, not 400
echo "2. Append batch 1 (2 events)"
batch1=$'{"e":"pageview","ts":1709568000000,"sid":"s1","url":"https://test.com"}\n{"e":"PURCHASE_CLICK","ts":1709568005000,"sid":"s1","url":"https://test.com"}\n'

resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE$API/$OBJECT_ID/ndjson/append/stream" \
  "${H[@]}" -H "Content-Type: application/x-ndjson" --data-binary "$batch1")
code=$(echo "$resp" | tail -1)
check "append batch 1" "$code" "200"

# Verify by reading back
items=$(curl -s "$BASE$API/$OBJECT_ID/ndjson/items/stream" "${H[@]}")
count=$(echo "$items" | grep -c '"e":' || true)
[[ "$count" -ge 2 ]] && ok "batch 1 written ($count events)" || ko "batch 1 failed ($count events)"
echo ""

# 3. Append batch 2 (1 event) — must return 200, not 400
echo "3. Append batch 2 (1 event)"
batch2=$'{"e":"LANDING_VISIT","ts":1709568010000,"sid":"s2","url":"https://test.com?utm_source=yt","utm_source":"yt"}\n'

resp=$(curl -s -w "\n%{http_code}" -X POST "$BASE$API/$OBJECT_ID/ndjson/append/stream" \
  "${H[@]}" -H "Content-Type: application/x-ndjson" --data-binary "$batch2")
code=$(echo "$resp" | tail -1)
check "append batch 2" "$code" "200"

items=$(curl -s "$BASE$API/$OBJECT_ID/ndjson/items/stream" "${H[@]}")
count=$(echo "$items" | grep -c '"e":' || true)
[[ "$count" -ge 3 ]] && ok "batch 2 written ($count events)" || ko "batch 2 failed ($count events)"
echo ""

# 4. Verify event content
echo "4. Verify event content"
has_pv=$(echo "$items" | grep -c '"e":"pageview"' || true)
has_pc=$(echo "$items" | grep -c '"e":"PURCHASE_CLICK"' || true)
has_lv=$(echo "$items" | grep -c '"e":"LANDING_VISIT"' || true)
[[ "$has_pv" -ge 1 && "$has_pc" -ge 1 && "$has_lv" -ge 1 ]] \
  && ok "all 3 event types present" || ko "missing events (pv=$has_pv pc=$has_pc lv=$has_lv)"

echo "$items" | grep -q '"utm_source":"yt"' \
  && ok "UTM fields preserved" || ko "UTM fields lost"
echo ""

# 5. List files
echo "5. List files"
list=$(curl -s "$BASE$API/list" "${H[@]}")
code_ok=$(echo "$list" | grep -c "$OBJECT_ID" || true)
[[ "$code_ok" -ge 1 ]] && ok "file in listing" || ko "file not in listing"

echo "$list" | grep -q "$FILE_NAME" \
  && ok "filename preserved" || ko "filename not in listing"
echo ""

# 6. Cleanup
echo "6. Cleanup"
code=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE$API/$OBJECT_ID" "${H[@]}")
check "delete file" "$code" "200"

list2=$(curl -s "$BASE$API/list" "${H[@]}")
echo "$list2" | grep -q "$OBJECT_ID" \
  && ko "file still in listing after delete" || ok "file removed from listing"
echo ""

# Summary
echo "=== Results: $pass passed, $fail failed ==="
[[ "$fail" -eq 0 ]] && exit 0 || exit 1
