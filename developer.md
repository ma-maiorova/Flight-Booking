# Flight Booking — Developer Guide: CI/CD & Observability

## Быстрый старт

```bash
# Запустить всю систему одной командой
bash scripts/run.sh

# Запустить E2E тест
bash scripts/e2e-test.sh

# Запустить нагрузочный тест
k6 run load-tests/booking-load-test.js
```

URL после запуска:
| Сервис | URL |
|---|---|
| booking-service REST | http://localhost:8080 |
| flight-service REST/actuator | http://localhost:8081 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |
| Alertmanager | http://localhost:9093 |

---

## CI/CD Pipeline

### Структура пайплайна

```
push / PR
    │
    ▼
┌──────────────────────────────────────────────────────────────────┐
│  Job 1: build                                                    │
│  mvn compile — компилирует все 3 модуля (shared-proto, flight,  │
│  booking). Быстрая проверка, что код собирается.                │
└──────────────────────┬───────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  Job 2: unit-tests (последовательно после build)                │
│  mvn test — запускает unit-тесты (Mockito, @WebMvcTest).        │
│  НЕ требует Docker, работает на чистом runner-е.                │
└──────────────────────┬───────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  Job 3: integration-tests (последовательно)                     │
│  mvn test -Dtest="**/*IntegrationTest" -Pintegration            │
│  Testcontainers поднимает PostgreSQL в Docker.                  │
│  Тесты проверяют реальные DB-запросы и gRPC-коммуникацию.       │
└──────────────────────┬───────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────┐
│  Job 4: e2e-and-load (последовательно)                          │
│  ├── docker compose up (вся система)                            │
│  ├── E2E тест (scripts/e2e-test.sh)                             │
│  ├── k6 нагрузочный тест (load-tests/booking-load-test.js)      │
│  ├── Валидация SLI из Prometheus                                │
│  └── docker compose down                                        │
└──────────────────────────────────────────────────────────────────┘
```

**Почему sequential, а не parallel?**
- Integration tests используют Testcontainers — это тяжёлые контейнеры, параллельный запуск увеличил бы потребление RAM в CI в 2x.
- E2E и load тесты требуют полностью рабочей системы, которая проходит integration tests.
- Порядок гарантирует: если unit test падает — мы не тратим 5 минут на Docker-запуск.

### Пайплайн падает при

- Ошибке компиляции (`mvn compile`)
- Провале любого unit или интеграционного теста
- Провале E2E скрипта (exit code 1)
- Провале k6 thresholds (error rate ≥ 1%, p95 ≥ 500ms)
- Нарушении SLI условий в Prometheus после нагрузочного теста

---

## Тесты

### Unit тесты (8 файлов)

| Файл | Что тестирует |
|---|---|
| `FlightServiceTest` | бизнес-логика сервиса: поиск, бронирование, кэш |
| `CacheServiceTest` | Redis кэш (mock RedisTemplate) |
| `BookingServiceTest` | создание/отмена бронирований |
| `BookingControllerTest` | REST API (MockMvc) |
| `FlightControllerTest` | REST API |
| `CircuitBreakerTest` | состояния автоматического выключателя |
| `CircuitBreakerAspectTest` | AOP-перехват вызовов |
| `RetryExecutorTest` | логика retry с backoff |

Запуск: `mvn test -Dspring.profiles.active=test`

### Интеграционные тесты

#### FlightServiceIntegrationTest (`flight-service`)

**Что поднимается:**
- PostgreSQL 16 в Testcontainers
- Liquibase мигрирует схему + seed-данные
- `@MockBean CacheService` — Redis не нужен

**Что проверяется:**
- `searchFlights` возвращает реальные данные из БД
- `getFlightById` находит и не находит рейс
- `reserveSeats` атомарно уменьшает `available_seats` в PostgreSQL
- Идемпотентность: повторный вызов с тем же `bookingId` не дублирует бронь
- `releaseReservation` возвращает места

Запуск: `mvn test -Dtest=FlightServiceIntegrationTest -Dspring.profiles.active=integration`

#### BookingIntegrationTest (`booking-service`)

**Что поднимается:**
- PostgreSQL 16 в Testcontainers
- Liquibase мигрирует схему
- `MockFlightServiceImpl` — встроенный gRPC-сервер на рандомном порту (запускается ДО Spring-контекста через static initializer, чтобы `@DynamicPropertySource` мог прочитать порт)
- Spring Boot с реальным gRPC-клиентом, настроенным на mock-сервер

**Что проверяется:**
1. POST /bookings → gRPC вызов getFlight + reserveSeats → запись в PostgreSQL
2. GET /bookings/{id} → чтение из PostgreSQL
3. POST /bookings/{id}/cancel → gRPC вызов releaseReservation → обновление статуса в PostgreSQL
4. 409 при повторной отмене
5. 404 при запросе несуществующего бронирования

**Почему mock gRPC, не реальный flight-service?**
Интеграционный тест проверяет *путь через gRPC* (сетевой вызов реально происходит), но не полный стек flight-service. Полный сквозной сценарий с двумя живыми сервисами проверяет E2E тест.

Запуск: `mvn test -Dtest=BookingIntegrationTest -Dspring.profiles.active=integration`

### E2E тест (`scripts/e2e-test.sh`)

Запускается на работающей системе (после `docker compose up`):

1. Поиск рейсов SVO → LED
2. Создание бронирования через REST API booking-service
3. Проверка в booking DB (GET /bookings/{id})
4. Проверка, что available_seats уменьшились (через gRPC → flight-service → flight DB)
5. Отмена бронирования
6. Проверка, что места вернулись
7. Проверка, что `/metrics` содержит `http_requests_total`

Запуск: `bash scripts/e2e-test.sh`

---

## Метрики

### Что экспортируют сервисы

Endpoint: `GET /metrics` (через Spring Boot Actuator, remapped с `/actuator/prometheus`)

#### Кастомные метрики (точные имена)

| Метрика | Тип | Labels | Описание |
|---|---|---|---|
| `http_requests_total` | Counter | method, endpoint, status | Все HTTP-запросы |
| `http_request_errors_total` | Counter | method, endpoint, error_type | Запросы со статусом 4xx/5xx |
| `http_request_duration_seconds` | Histogram | method, endpoint | Latency с перцентилями |

Реализованы через `MetricsFilter extends OncePerRequestFilter` в каждом сервисе. UUID в `endpoint` нормализуются в `{id}` чтобы избежать cardinality explosion.

#### Spring Boot auto-metrics

Помимо кастомных, Spring Boot Actuator автоматически экспортирует:
- `jvm_*` — метрики JVM
- `process_*` — метрики процесса
- `hikaricp_*` — пул соединений
- `http_server_requests_seconds` — встроенный таймер запросов

---

## SLI / SLO

### Определения

| SLI | Описание | SLO | Порог отказа |
|---|---|---|---|
| **API availability** | % успешных запросов (не 5xx) | ≥ 99% | < 95% |
| **End-to-end latency p95** | Время от запроса до ответа, 95-й перцентиль | < 500ms | > 1000ms |
| **End-to-end latency p99** | 99-й перцентиль | < 1000ms | > 2000ms |

### PromQL запросы для SLI

```promql
# API availability (последние 5 минут)
100 * (1 - sum(rate(http_request_errors_total{service="booking-service", error_type="server_error"}[5m]))
           / sum(rate(http_requests_total{service="booking-service"}[5m])))

# p95 latency
histogram_quantile(0.95,
  sum(rate(http_request_duration_seconds_bucket{service="booking-service"}[5m])) by (le)
)

# p99 latency
histogram_quantile(0.99,
  sum(rate(http_request_duration_seconds_bucket{service="booking-service"}[5m])) by (le)
)
```

### Обоснование порогов

- **p95 < 500ms**: типичный запрос бронирования включает 1 gRPC-вызов (getFlight) + 1 gRPC-вызов (reserveSeats) + 1 DB write. Тайм-аут gRPC — 30s по умолчанию, но 99% запросов должны укладываться в 500ms при нормальной нагрузке.
- **p95 > 1000ms = отказ**: бронирование, занимающее >1s, неприемлемо с точки зрения UX; также это сигнал насыщения ресурсов.
- **Error rate > 1% = предупреждение**: 1 из 100 запросов с ошибкой при нормальных условиях — аномалия.
- **Error rate > 10% = критично**: частичная деградация, вероятна проблема с flight-service или БД.

---

## Grafana дашборды

Grafana доступна по адресу http://localhost:3000 (admin/admin). Дашборды подгружаются автоматически при старте через provisioning.

### Services Dashboard (`monitoring/grafana/dashboards/services-dashboard.json`)

Отвечает на вопрос: **«Как сейчас работают сервисы?»** Содержит 9 панелей.

---

#### Панель 1 — Request Rate (RPS) — booking-service
**Тип:** timeseries

**PromQL:**
```promql
sum(rate(http_requests_total{service="booking-service"}[1m])) by (endpoint, method)
```

Показывает количество запросов в секунду к booking-service с разбивкой по HTTP-методу и endpoint. Позволяет видеть, какие именно ручки получают нагрузку и есть ли аномальные всплески трафика на конкретном endpoint.

---

#### Панель 2 — Request Rate (RPS) — flight-service
**Тип:** timeseries

**PromQL:**
```promql
sum(rate(http_requests_total{service="flight-service"}[1m])) by (endpoint, method)
```

Аналогичная панель для flight-service. Поскольку flight-service — чистый gRPC-сервер без REST, здесь отображаются метрики Actuator-запросов (health checks). При нагрузке на booking-service косвенно видна нагрузка через gRPC.

---

#### Панель 3 — Error Rate (%) — all services
**Тип:** timeseries

**PromQL:**
```promql
100 * sum(rate(http_request_errors_total[5m])) by (service)
      / sum(rate(http_requests_total[5m])) by (service)
```

Процент ошибочных запросов (4xx + 5xx) за скользящее окно 5 минут для каждого сервиса. Пороговые значения:
- зелёный: < 1%
- жёлтый: 1–5%
- красный: ≥ 5%

Именно эта панель является основным индикатором деградации: если линия пересекает 1% — нужно смотреть алерты.

---

#### Панель 4 — Latency Percentiles p50/p95/p99 — booking-service
**Тип:** timeseries

**PromQL:**
```promql
histogram_quantile(0.50, sum(rate(http_request_duration_seconds_bucket{service="booking-service"}[5m])) by (le))
histogram_quantile(0.95, sum(rate(http_request_duration_seconds_bucket{service="booking-service"}[5m])) by (le))
histogram_quantile(0.99, sum(rate(http_request_duration_seconds_bucket{service="booking-service"}[5m])) by (le))
```

Три линии на одном графике: медиана (p50), 95-й и 99-й перцентили latency. p95 — основной SLO-показатель (цель < 500ms). Расхождение между p50 и p99 указывает на хвостовые задержки — сигнал проблем с gRPC или БД на отдельных запросах.

---

#### Панель 5 — Latency Percentiles p50/p95/p99 — flight-service
**Тип:** timeseries

**PromQL:**
```promql
histogram_quantile(0.50, sum(rate(http_request_duration_seconds_bucket{service="flight-service"}[5m])) by (le))
histogram_quantile(0.95, sum(rate(http_request_duration_seconds_bucket{service="flight-service"}[5m])) by (le))
histogram_quantile(0.99, sum(rate(http_request_duration_seconds_bucket{service="flight-service"}[5m])) by (le))
```

Аналогично для flight-service. При сравнении с панелью 4: если latency booking-service высокая, а flight-service низкая — узкое место в самом booking-service или его БД. Если обе высокие — проблема в flight-service или сети между сервисами.

---

#### Панель 6 — Total Requests (5m) — booking-service
**Тип:** stat (одно число)

**PromQL:**
```promql
sum(increase(http_requests_total{service="booking-service"}[5m]))
```

Абсолютное количество запросов за последние 5 минут. Быстрый способ убедиться, что трафик вообще есть и система не простаивает.

---

#### Панель 7 — Total Requests (5m) — flight-service
**Тип:** stat (одно число)

**PromQL:**
```promql
sum(increase(http_requests_total{service="flight-service"}[5m]))
```

Аналогично для flight-service.

---

#### Панель 8 — SLO: Error Rate (target < 1%)
**Тип:** gauge

**PromQL:**
```promql
100 * sum(rate(http_request_errors_total[5m])) / sum(rate(http_requests_total[5m]))
```

Gauge с цветовой индикацией выполнения SLO по error rate (по обоим сервисам суммарно):
- зелёный: < 1% — SLO выполняется
- жёлтый: 1–5% — предупреждение
- красный: ≥ 5% — SLO нарушен

Стрелка gauge наглядно показывает текущее положение относительно порогов.

---

#### Панель 9 — SLO: p95 Latency booking-service (target < 500ms)
**Тип:** gauge

**PromQL:**
```promql
histogram_quantile(0.95,
  sum(rate(http_request_duration_seconds_bucket{service="booking-service"}[5m])) by (le)
)
```

Gauge для основного latency SLO. Пороги в секундах:
- зелёный: < 0.5s — SLO выполняется
- жёлтый: 0.5–1.0s — предупреждение
- красный: ≥ 1.0s — SLO нарушен

---

### Infrastructure Dashboard (`monitoring/grafana/dashboards/infrastructure-dashboard.json`)

Отвечает на вопрос: **«Где сейчас узкое место в инфраструктуре?»** Содержит 8 панелей.

---

#### Панель 1 — PostgreSQL Active Connections
**Тип:** timeseries

**PromQL:**
```promql
pg_stat_activity_count{datname="flight_db"}
pg_stat_activity_count{datname="booking_db"}
```

Количество активных соединений к каждой базе данных. Пороговые значения:
- зелёный: < 50
- жёлтый: 50–80
- красный: ≥ 80

HikariCP пул по умолчанию — 10 соединений на сервис. Резкий рост этого значения означает накопление незакрытых транзакций или connection leak.

---

#### Панель 2 — PostgreSQL Cache Hit Ratio (%)
**Тип:** timeseries

**PromQL:**
```promql
100 * pg_stat_database_blks_hit{datname="flight_db"}
      / (pg_stat_database_blks_hit{datname="flight_db"} + pg_stat_database_blks_read{datname="flight_db"} + 1)

100 * pg_stat_database_blks_hit{datname="booking_db"}
      / (pg_stat_database_blks_hit{datname="booking_db"} + pg_stat_database_blks_read{datname="booking_db"} + 1)
```

Процент страниц, отданных из shared_buffers (кэш PostgreSQL) без обращения к диску. Здоровое значение — > 95%. Падение ниже 80% говорит о недостаточном объёме `shared_buffers` или неэффективных запросах, которые сканируют много данных.

---

#### Панель 3 — Redis Memory Usage
**Тип:** timeseries

**PromQL:**
```promql
redis_memory_used_bytes   # текущее потребление
redis_memory_max_bytes    # лимит maxmemory
```

Два ряда на одном графике: сколько памяти Redis использует и какой установлен лимит. Когда `used` приближается к `max`, Redis начинает вытеснять ключи по политике `allkeys-lru`. Если сервис работает нормально, разрыв между линиями должен оставаться стабильным.

---

#### Панель 4 — Redis Commands Processed / sec
**Тип:** timeseries

**PromQL:**
```promql
rate(redis_commands_processed_total[1m])
```

Количество команд Redis в секунду (ops/sec). Отражает нагрузку на кэш: GET/SET для кэшированных рейсов и поиска. При нагрузочном тестировании значение должно коррелировать с RPS на booking-service.

---

#### Панель 5 — Redis Connected Clients
**Тип:** timeseries

**PromQL:**
```promql
redis_connected_clients
```

Текущее количество клиентов, подключённых к Redis. В штатном режиме — несколько соединений от Lettuce connection pool booking-service и flight-service. Аномальный рост указывает на утечку соединений или reconnect-шторм.

---

#### Панель 6 — Redis Keyspace Hit Rate (%)
**Тип:** stat (одно число)

**PromQL:**
```promql
100 * redis_keyspace_hits_total / (redis_keyspace_hits_total + redis_keyspace_misses_total + 1)
```

Процент cache hits от всех обращений к Redis. Пороги:
- красный: < 50% — кэш почти не работает
- жёлтый: 50–80%
- зелёный: ≥ 80% — кэш эффективен

Низкий hit rate при активной работе системы означает либо слишком короткий TTL, либо высокую cardinality ключей поиска.

---

#### Панель 7 — Redis Uptime
**Тип:** stat (одно число)

**PromQL:**
```promql
redis_uptime_in_seconds
```

Время работы Redis с последнего перезапуска в секундах. Используется для быстрой проверки: не перезапускался ли Redis (что сбрасывает кэш и может вызвать всплеск latency).

---

#### Панель 8 — PostgreSQL Query Rate (commits/sec)
**Тип:** timeseries

**PromQL:**
```promql
rate(pg_stat_database_xact_commit{datname="flight_db"}[1m])
rate(pg_stat_database_xact_commit{datname="booking_db"}[1m])
```

Количество успешно завершённых транзакций в секунду для каждой БД. Прямой индикатор write-нагрузки: каждое бронирование генерирует ~1 транзакцию в booking_db и обновление available_seats в flight_db. При нагрузочном тесте значения должны расти пропорционально RPS.

---

## Alert Rules (`monitoring/prometheus/alerts.yml`)

| Alert | Условие | Severity |
|---|---|---|
| `HighErrorRate` | error rate > 5% в течение 5 минут | warning |
| `CriticalErrorRate` | error rate > 10% в течение 2 минут | critical |
| `HighLatency` | p95 > 1s в течение 5 минут | warning |
| `CriticalLatency` | p99 > 2s в течение 2 минут | critical |
| `ServiceDown` | target недоступен 1 минуту | critical |
| `RedisHighMemory` | Redis использует > 80% памяти | warning |
| `PostgresHighConnections` | > 80 активных соединений | warning |

---

## Нагрузочный тест (`load-tests/booking-load-test.js`)

**Инструмент:** k6

**Профиль нагрузки:**
- 10s: 0 → 10 VU (ramp up)
- 30s: 10 → 20 VU (sustained)
- 10s: 20 → 0 VU (ramp down)

**Сценарий одного VU:**
1. GET /flights?origin=SVO&destination=LED
2. GET /flights/{id}
3. POST /bookings (+ cancel после создания, чтобы не исчерпать места)

**Thresholds (CI падает при нарушении):**
- `sli_error_rate < 1%`
- `sli_booking_duration_ms p(95) < 500ms`
- `http_req_duration p(95) < 1000ms`
- `http_req_failed rate < 1%`

---

## Инфраструктурные компоненты

| Компонент | Image | Port |
|---|---|---|
| Prometheus | `prom/prometheus:v2.52.0` | 9090 |
| Grafana | `grafana/grafana:10.4.3` | 3000 |
| Alertmanager | `prom/alertmanager:v0.27.0` | 9093 |
| postgres-exporter (flight) | `prometheuscommunity/postgres-exporter:v0.15.0` | 9187 |
| postgres-exporter (booking) | `prometheuscommunity/postgres-exporter:v0.15.0` | 9187 |
| redis-exporter | `oliver006/redis_exporter:v1.61.0` | 9121 |
