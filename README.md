# Flight Booking — CI/CD, Testing & Observability

Реализация всех трёх блоков задания. Система из двух Spring Boot микросервисов с gRPC, Redis Sentinel, PostgreSQL.

---

## Быстрый старт

```bash
bash scripts/run.sh          # поднять всё
bash scripts/e2e-test.sh     # E2E тест
```

| Сервис | URL |
|---|---|
| booking-service REST | http://localhost:8080 |
| flight-service actuator | http://localhost:8081 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |
| Alertmanager | http://localhost:9093 |

---

## Блок 1 — CI Pipeline, тестирование, метрики

### 1. CI Pipeline (`github/workflows/ci.yml`)

4 последовательных job-а, запускаются при push/PR в любую ветку:

```
build → unit-tests → integration-tests → e2e-and-load
```

- **build** — `mvn compile`, все 3 модуля (shared-proto, flight-service, booking-service)
- **unit-tests** — 53 теста, без Docker, `mvn test -Dspring.profiles.active=test`
- **integration-tests** — Testcontainers (PostgreSQL), 14 тестов
- **e2e-and-load** — `docker compose up` → E2E скрипт → k6 → валидация SLI из Prometheus

Pipeline падает при любой ошибке на любом шаге.

### 2. Интеграционные тесты

**`FlightServiceIntegrationTest`** (`flight-service`):
- Поднимает PostgreSQL через Testcontainers, мигрирует схему Liquibase
- Проверяет `searchFlights`, `getFlightById`, `reserveSeats` (идемпотентность, нехватка мест), `releaseReservation` — 9 тестов

**`BookingIntegrationTest`** (`booking-service`):
- PostgreSQL через Testcontainers + встроенный mock gRPC сервер на рандомном порту
- Проверяет полный путь: `POST /bookings` → gRPC `reserveSeats` → запись в PostgreSQL → `GET /bookings/{id}` → отмена → 409 при повторной отмене — 5 тестов

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test \
  -Dtest="**/*IntegrationTest" \
  -Dspring.profiles.active=integration \
  -Dsurefire.failIfNoSpecifiedTests=false
```

### 3. E2E тест (`scripts/e2e-test.sh`)

7 шагов на живой системе:
1. Поиск рейсов SVO → LED
2. Создание бронирования через REST booking-service
3. Проверка брони в БД (GET /bookings/{id})
4. Проверка, что `available_seats` уменьшились (через gRPC → flight-service)
5. Отмена бронирования
6. Проверка, что места вернулись
7. Проверка, что `/actuator/metrics` возвращает метрики

### 4. Prometheus + метрики (`/actuator/metrics`)

`MetricsFilter` (`OncePerRequestFilter`) в каждом сервисе записывает:

| Метрика | Тип | Labels |
|---|---|---|
| `http_requests_total` | Counter | method, endpoint, status |
| `http_request_errors_total` | Counter | method, endpoint, error_type |
| `http_request_duration_seconds` | Histogram | method, endpoint |

UUID в endpoint нормализуются в `{id}`. Actuator и `/metrics` пути пропускаются.

Prometheus scrape config: `metrics_path: /actuator/metrics`.

---

## Блок 2 — Grafana, инфраструктурный мониторинг, нагрузочные тесты

### 5. Grafana: дашборд сервисов (`monitoring/grafana/dashboards/services-dashboard.json`)

9 панелей, auto-provisioning через `monitoring/grafana/provisioning/`:
- Request Rate (RPS) — booking-service и flight-service
- Error Rate (%) — оба сервиса
- Latency p50/p95/p99 — оба сервиса
- Total Requests 5m — stat-панели
- SLO: Error Rate gauge (зелёный < 1%, жёлтый < 5%, красный > 5%)
- SLO: p95 Latency gauge (зелёный < 500ms, жёлтый < 1s, красный > 1s)

### 6. Grafana: дашборд инфраструктуры (`monitoring/grafana/dashboards/infrastructure-dashboard.json`)

8 панелей:
- PostgreSQL Active Connections (flight_db + booking_db)
- PostgreSQL Cache Hit Ratio
- Redis Memory Usage (used vs max)
- Redis Commands/sec
- Redis Connected Clients
- Redis Keyspace Hit Rate (stat)
- Redis Uptime (stat)
- PostgreSQL Commits/sec

Экспортёры в docker-compose: `postgres-exporter` x2, `redis-exporter`.

### 7. Нагрузочный тест (`load-tests/booking-load-test.js`)

Инструмент: **k6**. Профиль: 0→10→20→0 VU за 50 секунд.

Сценарий одного VU: `GET /flights` → `GET /flights/{id}` → `POST /bookings` → отмена.

Thresholds (CI падает при нарушении):
```javascript
sli_error_rate     < 0.01   // < 1% ошибок
sli_booking_p95    < 500    // p95 < 500ms
http_req_duration  p(95) < 1000
http_req_failed    rate < 0.01
```

---

## Блок 3 — Комплексная проверка, алерты, SLI/SLO

### 8. E2E + нагрузка + метрики в одном CI прогоне

В job `e2e-and-load`:
1. `docker compose up` — поднять всю систему
2. Ждать health-check-и обоих сервисов
3. Запустить `scripts/e2e-test.sh`
4. Запустить k6 (с thresholds)
5. `sleep 30` — дать Prometheus собрать данные
6. Запросить Prometheus API и проверить SLI:
   - error rate < 1%
   - p95 latency < 500ms

Артефакты: `load-test-results.json`, `load-test-summary.json`.

### 9. Alert Rules (`monitoring/prometheus/alerts.yml`)

7 правил, хранятся в репозитории, загружаются Prometheus при старте:

| Alert | Условие | Severity |
|---|---|---|
| `HighErrorRate` | error rate > 5% за 5 минут | warning |
| `CriticalErrorRate` | error rate > 10% за 2 минуты | critical |
| `HighLatency` | p95 > 1s за 5 минут | warning |
| `CriticalLatency` | p99 > 2s за 2 минуты | critical |
| `ServiceDown` | target недоступен 1 минуту | critical |
| `RedisHighMemory` | Redis > 80% памяти за 5 минут | warning |
| `PostgresHighConnections` | > 80 соединений | warning |

Alertmanager поднимается в docker-compose на порту 9093.

### 10. SLI / SLO

| SLI | SLO | Порог отказа | PromQL |
|---|---|---|---|
| API availability | ≥ 99% | < 95% | `1 - rate(http_request_errors_total{error_type="server_error"}[5m]) / rate(http_requests_total[5m])` |
| E2E latency p95 | < 500ms | > 1000ms | `histogram_quantile(0.95, sum(rate(http_request_duration_seconds_bucket[5m])) by (le))` |
| E2E latency p99 | < 1000ms | > 2000ms | `histogram_quantile(0.99, ...)` |

**Обоснование порогов:**
- p95 < 500ms: типичный запрос включает 2 gRPC-вызова + 1 DB write, это реально при нормальной нагрузке.
- p95 > 1000ms = отказ: бронирование дольше 1s — неприемлемо с точки зрения UX и сигнал насыщения ресурсов.
- Error rate > 1% = предупреждение: 1 из 100 запросов с ошибкой при нормальных условиях — аномалия.

SLI используются в CI (шаг «Validate SLI conditions from Prometheus») и в alert rules.

---

## Структура репозитория

```
.github/workflows/ci.yml          — CI/CD pipeline
scripts/
  run.sh                          — запуск системы одной командой
  e2e-test.sh                     — E2E тест
load-tests/booking-load-test.js   — k6 нагрузочный тест
monitoring/
  prometheus/prometheus.yml       — scrape config
  prometheus/alerts.yml           — alert rules
  grafana/provisioning/           — auto-provisioning datasource и дашбордов
  grafana/dashboards/             — JSON дашборды
  alertmanager/alertmanager.yml   — конфиг Alertmanager
docker-compose.yml                — вся система одной командой
developer.md                      — подробная документация CI/CD
github-actions-setup.md           — как настроить CI на GitHub
```
