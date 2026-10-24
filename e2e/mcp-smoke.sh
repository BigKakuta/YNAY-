#!/usr/bin/env bash
# MCP smoke test: drives POST /mcp like a real MCP client (Streamable HTTP, JSON responses).
#
#   e2e/mcp-smoke.sh                      # token from $YNAY_TOKEN or e2e/out/mcp-token.txt (written by ui-smoke.mjs)
#   YNAY_TOKEN=ynay_pat_... YNAY_URL=https://ynay.example.com e2e/mcp-smoke.sh
#   E2E_MCP_INSPECTOR=1 e2e/mcp-smoke.sh  # also list/call tools with the official MCP Inspector CLI (npx)
#
# It creates its own budget ("MCP smoke <timestamp>") with a Checking account, so it can run against
# any instance. Needs curl and jq.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
URL="${YNAY_URL:-${YNAY_PUBLIC_URL:-http://localhost:8080}}"
URL="${URL%/}"
TOKEN="${YNAY_TOKEN:-$(cat "$HERE/out/mcp-token.txt" 2>/dev/null || true)}"
TOKEN="$(tr -d '[:space:]' <<<"$TOKEN")"
[[ -n "$TOKEN" ]] || { echo "no token: set YNAY_TOKEN or run e2e/ui-smoke.mjs first" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

FAILS=0
ok() { printf 'ok   %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*"; FAILS=$((FAILS + 1)); }
jqt() { # jqt <json> <jq args...>: true when the filter yields true
    local json="$1"; shift
    jq -e "$@" <<<"$json" >/dev/null 2>&1
}
check() { # check <description> <command...>
    local desc="$1"; shift
    if "$@"; then ok "$desc"; else fail "$desc"; fi
}

ID=0
STATUS=""
BODY=""
# rpc <method> [params-json] [extra curl args...] -> sets STATUS and BODY
rpc() {
    local method="$1" params="${2:-}"; shift 2 || shift $#
    ID=$((ID + 1))
    local msg
    if [[ -n "$params" ]]; then
        msg="$(jq -cn --arg m "$method" --argjson p "$params" --argjson id "$ID" '{jsonrpc:"2.0",id:$id,method:$m,params:$p}')"
    else
        msg="$(jq -cn --arg m "$method" --argjson id "$ID" '{jsonrpc:"2.0",id:$id,method:$m}')"
    fi
    post "$msg" "$@"
}
post() { # post <body> [extra curl args...]
    local body="$1"; shift
    local out
    out="$(curl -sS -w '\n%{http_code}' "$URL/mcp" \
        -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -H 'Accept: application/json, text/event-stream' \
        -H 'MCP-Protocol-Version: 2025-06-18' \
        "$@" --data-binary "$body")"
    STATUS="${out##*$'\n'}"
    BODY="${out%$'\n'*}"
}
# call <tool> <arguments-json> -> sets RESULT (structuredContent) and IS_ERROR
call() {
    rpc tools/call "$(jq -cn --arg n "$1" --argjson a "$2" '{name:$n,arguments:$a}')"
    IS_ERROR="$(jq -r '.result.isError' <<<"$BODY")"
    RESULT="$(jq -c '.result.structuredContent' <<<"$BODY")"
    if [[ "$STATUS" != 200 || "$IS_ERROR" != false ]]; then
        echo "     $1 -> HTTP $STATUS: $(head -c 400 <<<"$BODY")"
    fi
}

echo "MCP smoke against $URL/mcp (token ${TOKEN:0:12}…)"

# --- transport and auth ------------------------------------------------------------------------
rpc initialize '{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"ynay-mcp-smoke","version":"1"}}'
check "initialize -> 200" test "$STATUS" = 200
check "initialize: protocolVersion 2025-06-18, serverInfo.name ynay, tools capability" \
    jqt "$BODY" '.result.protocolVersion == "2025-06-18" and .result.serverInfo.name == "ynay" and .result.capabilities.tools != null'

post '{"jsonrpc":"2.0","method":"notifications/initialized"}'
check "notifications/initialized -> 202 with empty body (got $STATUS)" test "$STATUS" = 202 -a -z "$BODY"

rpc ping
check "ping -> {}" jqt "$BODY" '.result == {}'

rpc tools/list
check "tools/list -> 200" test "$STATUS" = 200
TOOLS="$(jq -r '.result.tools[].name' <<<"$BODY")"
echo "     $(wc -l <<<"$TOOLS") tools: $(tr '\n' ' ' <<<"$TOOLS" | cut -c1-200)…"
for t in list_budgets list_accounts import_transactions get_month_budget create_transaction whoami; do
    check "tools/list has $t" grep -qx "$t" <<<"$TOOLS"
done
for t in list_api_tokens create_api_token revoke_api_token; do
    check "tools/list does NOT expose $t" bash -c "! grep -qx '$t' <<<\"\$0\"" "$TOOLS"
done
check "delete_budget is annotated destructiveHint, list_budgets readOnlyHint" jqt "$BODY" '
    (.result.tools[] | select(.name=="delete_budget") | .annotations.destructiveHint == true) and
    (.result.tools[] | select(.name=="list_budgets") | .annotations.readOnlyHint == true)'
check "every tool has an object inputSchema" jqt "$BODY" '[.result.tools[] | .inputSchema.type == "object"] | all'

rpc tools/call '{"name":"create_api_token","arguments":{"name":"sneaky"}}'
check "tools/call create_api_token -> JSON-RPC error -32602" jqt "$BODY" '.error.code == -32602'

rpc tools/list '' -H 'Origin: https://evil.example'
check "Origin https://evil.example -> 403 (got $STATUS)" test "$STATUS" = 403
rpc tools/list '' -H "Origin: $URL"
check "Origin $URL (own public URL) -> 200 (got $STATUS)" test "$STATUS" = 200

SAVED="$TOKEN"
TOKEN=""
rpc tools/list
check "no token -> 401 (got $STATUS)" test "$STATUS" = 401
HDR="$(curl -s -D - -o /dev/null "$URL/mcp" -H 'Content-Type: application/json' -d '{}' | tr -d '\r' | grep -i '^www-authenticate:' || true)"
check "401 carries WWW-Authenticate: Bearer resource_metadata=… ($HDR)" grep -q 'resource_metadata="[^"]*/.well-known/oauth-protected-resource"' <<<"$HDR"
TOKEN="ynay_pat_not_a_real_token"
rpc tools/list
check "bogus token -> 401 (got $STATUS)" test "$STATUS" = 401
TOKEN="$SAVED"
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$URL/mcp" -H "Authorization: Bearer $TOKEN")"
check "GET /mcp -> 405 (got $CODE)" test "$CODE" = 405

# --- tools --------------------------------------------------------------------------------------
call whoami '{}'
echo "     whoami: $RESULT"
check "whoami returns the token's user" jqt "$RESULT" '.username | length > 0'

NAME="MCP smoke $(date -u +%Y-%m-%dT%H:%M:%S)"
call create_budget "$(jq -cn --arg n "$NAME" '{name:$n}')"
BUDGET="$(jq -r '.id' <<<"$RESULT")"
check "create_budget -> $BUDGET" test "$IS_ERROR" = false

call list_budgets '{}'
check "list_budgets (wrapped as {result:[...]}) contains the new budget" \
    jqt "$RESULT" --arg b "$BUDGET" '.result | map(.id) | index($b) != null'
check "list_budgets text content is the same JSON" \
    jqt "$BODY" '(.result.content[0].text | fromjson) == .result.structuredContent.result'

TODAY="$(date +%Y-%m-%d)"
MONTH="${TODAY:0:7}"
call create_account "$(jq -cn --arg b "$BUDGET" --arg d "${MONTH}-01" '{budgetId:$b,name:"Checking",type:"CHECKING",startingBalance:1000000,startingDate:$d}')"
ACCOUNT="$(jq -r '.id' <<<"$RESULT")"
check "create_account Checking with 1,000.00" jqt "$RESULT" '.balance == 1000000 and .onBudget'

call list_accounts "$(jq -cn --arg b "$BUDGET" '{budgetId:$b}')"
check "list_accounts has Checking" jqt "$RESULT" --arg a "$ACCOUNT" '.result | map(.id) | index($a) != null'

call list_categories "$(jq -cn --arg b "$BUDGET" '{budgetId:$b}')"
GROCERIES="$(jq -r '.result[].categories[] | select(.name=="Groceries") | .id' <<<"$RESULT")"
check "list_categories has Groceries" test -n "$GROCERIES"

# A CSV statement, converted to ImportLines the way an agent would. Two identical coffees on the
# same day must both be imported once, and both be recognized as duplicates the second time.
CSV="Date,Payee,Amount,Memo
${MONTH}-02,Coffee Corner,-4.50,latte
${MONTH}-02,Coffee Corner,-4.50,latte
${MONTH}-03,Fresh Market,-62.35,
${MONTH}-05,ACME Payroll,2500.00,salary"
LINES="$(tail -n +2 <<<"$CSV" | jq -R -s -c --arg g "$GROCERIES" '
    split("\n") | map(select(length > 0) | split(",")) |
    map({date: .[0], payeeName: .[1], amount: ((.[2] | tonumber) * 1000 | round)}
        + (if (.[3] // "") != "" then {memo: .[3]} else {} end)
        + (if .[1] == "Fresh Market" then {categoryId: $g} else {} end))')"
ARGS="$(jq -cn --arg b "$BUDGET" --arg a "$ACCOUNT" --argjson l "$LINES" '{budgetId:$b,accountId:$a,transactions:$l}')"

call import_transactions "$ARGS"
echo "     first import: $RESULT"
check "first import: 4 imported, 0 duplicates" jqt "$RESULT" '.imported == 4 and .duplicates == 0'
call import_transactions "$ARGS"
echo "     second import: $RESULT"
check "second import: 0 imported, 4 duplicates (dedupe on generated import ids)" \
    jqt "$RESULT" '.imported == 0 and .duplicates == 4 and (.duplicateImportIds | length) == 4'

call list_transactions "$(jq -cn --arg b "$BUDGET" --arg a "$ACCOUNT" '{budgetId:$b,accountId:$a,unapprovedOnly:true}')"
check "imported transactions are unapproved (4)" jqt "$RESULT" '.total == 4 and ([.transactions[] | .approved == false] | all)'
check "import ids look like YNAY:<amount>:<date>:<occurrence>" \
    jqt "$RESULT" '[.transactions[].importId] | sort == (["YNAY:-4500:'"${MONTH}"'-02:1","YNAY:-4500:'"${MONTH}"'-02:2","YNAY:-62350:'"${MONTH}"'-03:1","YNAY:2500000:'"${MONTH}"'-05:1"] | sort)'

call get_month_budget "$(jq -cn --arg b "$BUDGET" --arg m "$MONTH" '{budgetId:$b,month:$m}')"
echo "     month: $(jq -c '{readyToAssign,income,activity,uncategorized}' <<<"$RESULT")"
check "get_month_budget: income = starting balance only (payroll and coffees are uncategorized: +2,491.00), Groceries activity -62.35" \
    jqt "$RESULT" --arg g "$GROCERIES" '.income == 1000000 and .readyToAssign == 1000000 and .uncategorized == 2491000
        and ([.groups[].categories[] | select(.categoryId == $g) | .activity] == [-62350])'

call assign_to_category "$(jq -cn --arg b "$BUDGET" --arg m "$MONTH" --arg g "$GROCERIES" '{budgetId:$b,month:$m,categoryId:$g,assigned:100000}')"
check "assign_to_category Groceries 100.00 -> available 37.65" jqt "$RESULT" '.available == 37650'

call get_month_budget "$(jq -cn --arg b "$BUDGET" '{budgetId:$b,month:"2026-1"}')"
check "bad month format -> tool error (isError true)" test "$IS_ERROR" = true

# --- a real MCP client ---------------------------------------------------------------------------
if [[ "${E2E_MCP_INSPECTOR:-}" == 1 ]]; then
    echo "     MCP Inspector CLI (npx @modelcontextprotocol/inspector --cli)…"
    INSPECT=(npx -y @modelcontextprotocol/inspector@2.9.0 --cli "$URL/mcp" --transport http
        --header "Authorization: Bearer $TOKEN")
    OUT="$("${INSPECT[@]}" --method tools/list 2>&1)"
    check "inspector tools/list lists import_transactions, not create_api_token" \
        jqt "$OUT" '[.tools[].name] | index("import_transactions") != null and index("create_api_token") == null'
    OUT="$("${INSPECT[@]}" --method tools/call --tool-name list_accounts --tool-arg "budgetId=$BUDGET" 2>&1)"
    check "inspector tools/call list_accounts" jqt "$OUT" --arg a "$ACCOUNT" '.isError == false and (.structuredContent.result | map(.id) | index($a) != null)'
    OUT2="$("${INSPECT[@]}" --method tools/call --tool-name get_month_budget --tool-arg "budgetId=$BUDGET" "month=$MONTH" 2>&1)"
    check "inspector tools/call get_month_budget" jqt "$OUT2" '.isError == false and .structuredContent.readyToAssign == 900000'
    [[ $FAILS -eq 0 ]] || { head -c 600 <<<"$OUT"; head -c 600 <<<"$OUT2"; }
fi

call delete_budget "$(jq -cn --arg b "$BUDGET" '{budgetId:$b}')"
check "delete_budget (cleanup)" jqt "$RESULT" '.deleted == true'

echo
if [[ $FAILS -eq 0 ]]; then echo "MCP SMOKE PASSED"; else echo "MCP SMOKE FAILED ($FAILS)"; exit 1; fi
