#!/usr/bin/env bash
set -euo pipefail

BOOKING_URL="${BOOKING_URL:-http://localhost:8080}"
FLIGHT_URL="${FLIGHT_URL:-http://localhost:8081}"
MAX_WAIT="${MAX_WAIT:-120}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

# ─── Wait for services ────────────────────────────────────────────────────────
wait_ready() {
  local url="$1"; local name="$2"; local waited=0
  info "Waiting for $name ($url)..."
  until curl -sf "$url/actuator/health/liveness" >/dev/null 2>&1; do
    if (( waited >= MAX_WAIT )); then
      fail "$name did not become ready in ${MAX_WAIT}s"
    fi
    sleep 2; (( waited += 2 ))
  done
  pass "$name is ready"
}

wait_ready "$FLIGHT_URL"  "flight-service"
wait_ready "$BOOKING_URL" "booking-service"

# ─── Step 1: Search flights ───────────────────────────────────────────────────
info "Step 1: Search flights SVO → LED"
SEARCH=$(curl -sf "$BOOKING_URL/flights?origin=SVO&destination=LED")
FLIGHT_COUNT=$(echo "$SEARCH" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
[[ "$FLIGHT_COUNT" -gt 0 ]] || fail "Expected flights, got 0"
pass "Found $FLIGHT_COUNT flights SVO → LED"

# Pick first flight id
FLIGHT_ID=$(echo "$SEARCH" | python3 -c "import sys,json; data=json.load(sys.stdin); print(data[0]['id'])")
PRICE=$(echo "$SEARCH" | python3 -c "import sys,json; data=json.load(sys.stdin); print(data[0]['price'])")
AVAIL_BEFORE=$(echo "$SEARCH" | python3 -c "import sys,json; data=json.load(sys.stdin); print(data[0]['availableSeats'])")
info "Using flight $FLIGHT_ID, price=$PRICE, availableSeats=$AVAIL_BEFORE"

# ─── Step 2: Create booking ───────────────────────────────────────────────────
info "Step 2: Create booking via booking-service REST API"
USER_ID="e2e00000-0000-0000-0000-000000000001"

BOOKING=$(curl -sf -X POST "$BOOKING_URL/bookings" \
  -H "Content-Type: application/json" \
  -d "{
    \"userId\": \"$USER_ID\",
    \"flightId\": \"$FLIGHT_ID\",
    \"passengerName\": \"E2E Test User\",
    \"passengerEmail\": \"e2e@example.com\",
    \"seatCount\": 2
  }")

BOOKING_STATUS=$(echo "$BOOKING" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
BOOKING_ID=$(echo "$BOOKING"    | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
TOTAL_PRICE=$(echo "$BOOKING"   | python3 -c "import sys,json; print(json.load(sys.stdin)['totalPrice'])")

[[ "$BOOKING_STATUS" == "CONFIRMED" ]] || fail "Expected status CONFIRMED, got $BOOKING_STATUS"
pass "Booking created: id=$BOOKING_ID status=$BOOKING_STATUS totalPrice=$TOTAL_PRICE"

# ─── Step 3: Verify booking in DB (via GET) ───────────────────────────────────
info "Step 3: Verify booking persisted in booking-service DB"
FETCHED=$(curl -sf "$BOOKING_URL/bookings/$BOOKING_ID")
FETCHED_STATUS=$(echo "$FETCHED" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
[[ "$FETCHED_STATUS" == "CONFIRMED" ]] || fail "Fetched booking status=$FETCHED_STATUS, expected CONFIRMED"
pass "Booking $BOOKING_ID found in DB with status=CONFIRMED"

# ─── Step 4: Verify seat count decreased in flight-service ───────────────────
info "Step 4: Verify available seats decreased via gRPC / flight-service"
FLIGHT_DATA=$(curl -sf "$BOOKING_URL/flights/$FLIGHT_ID")
AVAIL_AFTER=$(echo "$FLIGHT_DATA" | python3 -c "import sys,json; print(json.load(sys.stdin)['availableSeats'])")
EXPECTED=$(( AVAIL_BEFORE - 2 ))

[[ "$AVAIL_AFTER" -eq "$EXPECTED" ]] || \
  fail "Expected availableSeats=$EXPECTED after booking 2 seats, got $AVAIL_AFTER"
pass "Available seats decreased from $AVAIL_BEFORE to $AVAIL_AFTER (reserved 2)"

# ─── Step 5: Cancel booking ───────────────────────────────────────────────────
info "Step 5: Cancel booking"
CANCEL=$(curl -sf -X POST "$BOOKING_URL/bookings/$BOOKING_ID/cancel")
CANCEL_STATUS=$(echo "$CANCEL" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
[[ "$CANCEL_STATUS" == "CANCELLED" ]] || fail "Expected CANCELLED, got $CANCEL_STATUS"
pass "Booking $BOOKING_ID cancelled"

# ─── Step 6: Verify seats returned ───────────────────────────────────────────
info "Step 6: Verify seats returned after cancel"
sleep 1   # allow propagation
FLIGHT_RESTORED=$(curl -sf "$BOOKING_URL/flights/$FLIGHT_ID")
AVAIL_RESTORED=$(echo "$FLIGHT_RESTORED" | python3 -c "import sys,json; print(json.load(sys.stdin)['availableSeats'])")
[[ "$AVAIL_RESTORED" -eq "$AVAIL_BEFORE" ]] || \
  fail "Expected seats restored to $AVAIL_BEFORE, got $AVAIL_RESTORED"
pass "Seats restored to $AVAIL_RESTORED"

# ─── Step 7: Verify metrics endpoints ────────────────────────────────────────
info "Step 7: Verify Prometheus metrics endpoints"
curl -sf "$BOOKING_URL/actuator/metrics" | grep -q "http_requests_total" || \
  fail "booking-service /actuator/metrics missing http_requests_total"
pass "booking-service /actuator/metrics contains http_requests_total"

curl -sf "$FLIGHT_URL/actuator/metrics" | grep -q "jvm_memory_used_bytes" || \
  fail "flight-service /actuator/metrics not responding with JVM metrics"
pass "flight-service /actuator/metrics is accessible and contains JVM metrics"

echo ""
echo -e "${GREEN}═══════════════════════════════════════${NC}"
echo -e "${GREEN} E2E TEST PASSED — all 7 steps OK      ${NC}"
echo -e "${GREEN}═══════════════════════════════════════${NC}"
