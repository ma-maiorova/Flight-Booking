import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ─── Custom SLI metrics ───────────────────────────────────────────────────────
const errorRate = new Rate('sli_error_rate');
const bookingDuration = new Trend('sli_booking_duration_ms', true);

// ─── Load profile ─────────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    ramp_up: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 10 },   // ramp up
        { duration: '30s', target: 20 },   // sustained load
        { duration: '10s', target: 0 },    // ramp down
      ],
    },
  },

  // ─── SLO thresholds — CI fails if violated ─────────────────────────────────
  thresholds: {
    // Error rate < 1%
    'sli_error_rate': ['rate<0.01'],
    // p95 booking latency < 500ms
    'sli_booking_duration_ms': ['p(95)<500'],
    // Overall p95 < 1000ms
    'http_req_duration': ['p(95)<1000'],
    // < 1% failures globally
    'http_req_failed': ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Known flight from seed data
const FLIGHT_ID = '10000000-0000-0000-0000-000000000002';

// ─── Main scenario ─────────────────────────────────────────────────────────────
export default function () {
  const userId = generateUUID();

  group('search flights', () => {
    const res = http.get(`${BASE_URL}/flights?origin=SVO&destination=LED`, {
      tags: { name: 'search' },
    });
    check(res, {
      'search status 200': (r) => r.status === 200,
    });
    errorRate.add(res.status >= 400 || res.status === 0);
  });

  sleep(0.1);

  group('get flight by id', () => {
    const res = http.get(`${BASE_URL}/flights/${FLIGHT_ID}`, {
      tags: { name: 'get_flight' },
    });
    check(res, {
      'get flight status 200': (r) => r.status === 200,
    });
    errorRate.add(res.status >= 400 || res.status === 0);
  });

  sleep(0.1);

  group('create booking', () => {
    const payload = JSON.stringify({
      userId: userId,
      flightId: FLIGHT_ID,
      passengerName: 'Load Test User',
      passengerEmail: `loadtest-${userId}@example.com`,
      seatCount: 1,
    });

    const start = Date.now();
    const res = http.post(`${BASE_URL}/bookings`, payload, {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'create_booking' },
    });
    const elapsed = Date.now() - start;

    const ok = check(res, {
      'booking created (201)': (r) => r.status === 201,
      'booking has id': (r) => {
        try { return JSON.parse(r.body).id !== undefined; } catch { return false; }
      },
    });

    bookingDuration.add(elapsed);
    errorRate.add(!ok || res.status >= 400 || res.status === 0);

    // Cancel booking to avoid exhausting seats during long runs
    if (res.status === 201) {
      try {
        const bookingId = JSON.parse(res.body).id;
        sleep(0.05);
        http.post(`${BASE_URL}/bookings/${bookingId}/cancel`, null, {
          tags: { name: 'cancel_booking' },
        });
      } catch (_) {}
    }
  });

  sleep(0.2);
}

// ─── Helpers ──────────────────────────────────────────────────────────────────
function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}
