#!/usr/bin/env bash
# Reproducible Phase 1 demo against the running docker compose stack.
# Requires: curl, jq.   Usage: ./scripts/demo.sh
set -euo pipefail

API=${HOOKGUARD_API:-http://localhost:8080}
RECEIVER=${HOOKGUARD_RECEIVER:-http://localhost:9090}
# The URL HookGuard uses to reach the receiver (inside the compose network).
TARGET=${HOOKGUARD_TARGET:-http://demo-receiver:9090}

register() { # $1 = path on the receiver, $2 = description
  curl -sf -X POST "$API/api/endpoints" -H 'Content-Type: application/json' \
    -d "{\"url\":\"$TARGET$1\",\"description\":\"$2\"}" | jq -r .id
}

submit() { # $1 = endpoint id, $2 = event type, $3 = JSON payload
  curl -sf -X POST "$API/api/events" -H 'Content-Type: application/json' \
    -d "{\"endpointId\":\"$1\",\"eventType\":\"$2\",\"payload\":$3}" | jq -r .deliveryId
}

status_of() { curl -sf "$API/api/deliveries/$1" | jq -r .status; }

wait_terminal() { # $1 = delivery id
  for _ in $(seq 1 60); do
    s=$(status_of "$1")
    [[ $s == DELIVERED || $s == FAILED ]] && { echo "$s"; return; }
    sleep 0.5
  done
  echo "$s (still not terminal after 30s)"
}

echo "== Resetting demo receiver"
curl -sf -X DELETE "$RECEIVER/received" >/dev/null
curl -sf -X PUT "$RECEIVER/control/outage" -H 'Content-Type: application/json' -d '{"enabled":false}' >/dev/null

echo "== Rejected registration (not in the allowlist):"
curl -s -X POST "$API/api/endpoints" -H 'Content-Type: application/json' \
  -d '{"url":"http://169.254.169.254/latest/meta-data"}' | jq -c '{status, errors}'

echo "== Registering endpoints"
OK=$(register /hooks/ok "Healthy receiver")
FAIL=$(register /hooks/fail "Always 500")
SLOW=$(register "/hooks/slow?delayMs=8000" "Slower than the 5s timeout")
FLAKY=$(register "/hooks/flaky?failTimes=1" "Fails once per delivery")

echo "== Submitting events"
D_OK=$(submit "$OK" order.created '{"orderId":1001,"total":49.90}')
D_FAIL=$(submit "$FAIL" order.created '{"orderId":1002}')
D_SLOW=$(submit "$SLOW" order.created '{"orderId":1003}')
D_FLAKY=$(submit "$FLAKY" order.created '{"orderId":1004}')

printf '%-22s %s\n' "healthy  ($D_OK)" "$(wait_terminal "$D_OK")"
printf '%-22s %s\n' "500      ($D_FAIL)" "$(wait_terminal "$D_FAIL")"
printf '%-22s %s\n' "timeout  ($D_SLOW)" "$(wait_terminal "$D_SLOW")"
printf '%-22s %s\n' "flaky    ($D_FLAKY)" "$(wait_terminal "$D_FLAKY")   <- Phase 1 has no retries; Phase 2 turns this into DELIVERED"

echo "== Receiver outage"
curl -sf -X PUT "$RECEIVER/control/outage" -H 'Content-Type: application/json' -d '{"enabled":true}' >/dev/null
D_OUTAGE=$(submit "$OK" order.created '{"orderId":1005}')
printf '%-22s %s\n' "outage   ($D_OUTAGE)" "$(wait_terminal "$D_OUTAGE")"
curl -sf -X PUT "$RECEIVER/control/outage" -H 'Content-Type: application/json' -d '{"enabled":false}' >/dev/null

echo "== Attempt history of the timed-out delivery"
curl -sf "$API/api/deliveries/$D_SLOW" | jq -c '.attempts[] | {attemptNumber, outcome, httpStatus, durationMs, errorMessage}'

echo "== Stats"
curl -sf "$API/api/stats" | jq -c .

echo
echo "Open the dashboard: http://localhost:3000"
