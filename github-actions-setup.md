# GitHub Actions CI/CD Setup Guide

## Prerequisites

1. GitHub account with a repository containing this project
2. Repository must be public, or you have GitHub Actions minutes available

## 1. Push the code to GitHub

```bash
git remote add origin https://github.com/<username>/flight-booking.git
git push -u origin main
```

## 2. The pipeline runs automatically

The workflow is defined in `.github/workflows/ci.yml`. It triggers on every push and pull request to any branch.

No additional setup is required — the workflow uses only public Docker images and Maven.

## 3. Pipeline structure

```
push / PR → build → unit-tests → integration-tests → e2e-and-load
```

| Job | Runs | What it does |
|---|---|---|
| `build` | always | `mvn compile` — verifies the code compiles |
| `unit-tests` | after build | `mvn test` — 53 unit tests, no Docker |
| `integration-tests` | after unit | Testcontainers (PostgreSQL) — 14 integration tests |
| `e2e-and-load` | after integration | Docker Compose + E2E + k6 + Prometheus SLI validation |

## 4. Environment variables

The workflow sets these in the `env:` block at the top of `ci.yml` — no secrets required for testing:

| Variable | Value | Used by |
|---|---|---|
| `JAVA_VERSION` | `25` | All Java build steps |
| `FLIGHT_SERVICE_PASSWORD` | `test-api-key` | gRPC auth between services |
| `FLIGHT_DB_PASSWORD` | `flight_pass` | PostgreSQL for flight-service |
| `BOOKING_DB_PASSWORD` | `booking_pass` | PostgreSQL for booking-service |

If you want to use real secrets (e.g., in production-like environments), store them under **Settings → Secrets and variables → Actions**, then reference them with `${{ secrets.SECRET_NAME }}`.

## 5. Viewing results

After a push, go to the **Actions** tab in your GitHub repository:

- Each run shows the 4 jobs in a dependency graph
- Click any job to see logs
- The `unit-tests` and `integration-tests` jobs publish test results via `dorny/test-reporter` — visible as check annotations on the commit
- The `e2e-and-load` job uploads k6 results as an artifact (downloadable from the run summary page)

## 6. When the pipeline fails

| Failure | Likely cause | How to fix |
|---|---|---|
| `build` fails | Compilation error | Fix the code, push again |
| `unit-tests` fails | Unit test assertion or context wiring | Run `mvn test -Dspring.profiles.active=test` locally |
| `integration-tests` fails | DB schema mismatch or gRPC contract issue | Run `TESTCONTAINERS_RYUK_DISABLED=true mvn test -Dtest="**/*IntegrationTest" -Dspring.profiles.active=integration` locally |
| `e2e-and-load` fails | Service didn't start, E2E assertion, or SLO threshold crossed | Check `Show service logs on failure` step in the run; check k6 summary artifact |

## 7. Local equivalent of the CI run

```bash
# Unit tests
mvn test -Dspring.profiles.active=test

# Integration tests (requires Docker)
TESTCONTAINERS_RYUK_DISABLED=true mvn test \
  -Dtest="**/*IntegrationTest" \
  -Dspring.profiles.active=integration \
  -Dsurefire.failIfNoSpecifiedTests=false

# E2E + load tests (requires docker-compose up first)
bash scripts/run.sh up
bash scripts/e2e-test.sh
k6 run load-tests/booking-load-test.js
```

## 8. Adding a new branch protection rule (optional)

To require the CI to pass before merging pull requests:

1. Go to **Settings → Branches → Add rule**
2. Branch name pattern: `main`
3. Check **Require status checks to pass before merging**
4. Search for and add: `Build`, `Unit Tests`, `Integration Tests`, `E2E + Load Tests + Metrics Validation`
5. Save the rule

## 9. Prometheus SLI validation in CI

The `e2e-and-load` job validates SLIs from Prometheus after the load test:

```
Error rate (booking-service) < 1%
p95 latency (booking-service) < 500ms
```

If these thresholds are violated, the pipeline fails. The thresholds are defined in `.github/workflows/ci.yml` in the **Validate SLI conditions from Prometheus** step.
