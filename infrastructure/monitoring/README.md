# CloudForge Monitoring, Observability & Alerting Runbook

This directory contains the **Phase 4 Observability Stack** for CloudForge, deployed via the optional Docker Compose overlay [`compose.monitoring.yaml`](../../compose.monitoring.yaml).

> **Render Free-Plan Boundary:** Prometheus, Grafana, Alertmanager, OpenTelemetry Collector, and exporters are **not** deployed inside the Render free web service (`cloudforge-staging`). On Render, only public health probes (`GET /api/health`, `GET /actuator/health`, `GET /actuator/info`) are exposed, `management.tracing.enabled` is locked `false`, and `/actuator/prometheus` is excluded because the `monitoring` Spring profile is inactive.

---

## 1. Architecture & Components

| Service | Image | Purpose | Network / Exposure |
| --- | --- | --- | --- |
| **Spring Boot Backend (`monitoring` profile)** | `cloudforge-backend` | Exposes `/actuator/prometheus` via Micrometer (`http_server_requests_seconds_*`, `hikaricp_*`, `jvm_*`, `cloudforge_operations_total`) and ECS structured logs (`application-monitoring.yml`) | Internal `cloudforge-net:8080`; `/actuator/prometheus` protected by `@Order(0)` `ScrapeSecurity` Bearer token (>= 32 chars, `GET` only, constant-time compare) |
| **Prometheus** | `prom/prometheus:v3.5.0` | Scrapes backend (`/actuator/prometheus` with Docker secret bearer token), PostgreSQL exporter, and worker textfile exporter; evaluates `alerts.yml`; 7d / 2 GB TSDB retention | Internal `cloudforge-net` only |
| **Alertmanager** | `prom/alertmanager:v0.28.1` | Groups and routes firing alerts (`alertmanager/alertmanager.yml`) | Internal `cloudforge-net` only |
| **Grafana** | `grafana/grafana:12.1.1` | Pre-provisioned Prometheus datasource (`uid: cloudforge`) and 5 operational dashboards; sign-up disabled; admin password from Docker secret | `127.0.0.1:3000` (localhost-only binding) |
| **PostgreSQL Exporter** | `prometheuscommunity/postgres-exporter:v0.17.1` | Exports `pg_up` and PostgreSQL metrics using a dedicated `pg_monitor` role | Internal `cloudforge-net:9187` |
| **Worker Exporter** | `prom/node-exporter:v1.9.1` | `--collector.disable-defaults --collector.textfile` exporting `cloudforge.prom` atomically written by `WorkerTelemetry` | Internal `cloudforge-net:9100` |
| **OpenTelemetry Collector** | `otel/opentelemetry-collector:0.131.0` | Optional OTLP HTTP receiver (`:4318`) with 128 MiB memory limiter and debug exporter when `CLOUDFORGE_TRACING_ENABLED=true` | Internal `cloudforge-net:4318` |

---

## 2. Provisioned Grafana Dashboards

Located under `infrastructure/monitoring/grafana/dashboards/`:

1. **`overview.json` (`cf-overview`):** API availability (`up{job="backend"}`), request rate (`http_server_requests_seconds_count`), p95 latency (`http_server_requests_seconds_bucket`).
2. **`pipelines.json` (`cf-pipelines`):** Pipeline event throughput (`cloudforge_operations_total{action=~"PIPELINE_.*"}`) and active worker assignments (`cloudforge_worker_active_assignments`).
3. **`build-registry.json` (`cf-build-registry`):** OCI build and publish lifecycle events (`cloudforge_operations_total{action=~"OCI_.*"}`).
4. **`kubernetes.json` (`cf-kubernetes`):** Kubernetes deployment, rollback, and reconciliation events (`cloudforge_operations_total{action=~"KUBERNETES_.*"}`).
5. **`infrastructure.json` (`cf-infrastructure`):** JVM heap/non-heap usage (`jvm_memory_used_bytes`), database availability (`pg_up`), HikariCP active connections (`hikaricp_connections_active`), and worker heartbeat age (`time() - cloudforge_worker_last_heartbeat_seconds`).

---

## 3. Starting the Monitoring Stack Locally

Never commit secret files or credentials to Git. Store temporary local secret files outside the repository (e.g., `$env:TEMP\cloudforge-mon-secrets\` or `/tmp/cloudforge-mon-secrets/`):

1. Create a read-only `pg_monitor` role in PostgreSQL (do not reuse the superuser/app password):
   ```sql
   CREATE ROLE cloudforge_monitor LOGIN PASSWORD '<strong-monitor-password>';
   GRANT pg_monitor TO cloudforge_monitor;
   ```
2. Create local secret files with strict permissions (`>= 32` characters for the Prometheus bearer token):
   ```powershell
   $secDir = Join-Path $env:TEMP "cloudforge-mon-secrets"
   New-Item -ItemType Directory -Force -Path $secDir, (Join-Path $secDir "worker-metrics") | Out-Null
   # Write without trailing newline (ScrapeSecurity also trims whitespace)
   [IO.File]::WriteAllText((Join-Path $secDir "metrics_token"), "<32+-char-random-token>")
   [IO.File]::WriteAllText((Join-Path $secDir "grafana_password"), "<strong-grafana-admin-password>")
   [IO.File]::WriteAllText((Join-Path $secDir "exporter_password"), "<strong-monitor-password>")
   ```
3. Export secret file paths and launch the combined Compose stack:
   ```powershell
   $env:MONITORING_DB_USER = "cloudforge_monitor"
   $env:WORKER_METRICS_DIR = Join-Path $secDir "worker-metrics"
   $env:METRICS_TOKEN_FILE = Join-Path $secDir "metrics_token"
   $env:GRAFANA_PASSWORD_FILE = Join-Path $secDir "grafana_password"
   $env:EXPORTER_PASSWORD_FILE = Join-Path $secDir "exporter_password"
   docker compose -f compose.yaml -f compose.monitoring.yaml up -d --build
   ```
4. *(Optional)* Point any local worker at the metrics directory (`CLOUDFORGE_WORKER_METRICS_DIR=$env:WORKER_METRICS_DIR`) so `WorkerTelemetry` publishes `cloudforge.prom` on each heartbeat.
5. Open Grafana at `http://127.0.0.1:3000` (`admin` + password from `grafana_password`).

---

## 4. Validating Configuration Without Starting the Full Stack

```bash
docker compose -f compose.yaml -f compose.monitoring.yaml config --quiet
docker run --rm -v "$PWD/infrastructure/monitoring/prometheus:/etc/prometheus:ro" \
  -v /dev/null:/run/secrets/metrics_token:ro \
  --entrypoint promtool prom/prometheus:v3.5.0 check config /etc/prometheus/prometheus.yml
docker run --rm -v "$PWD/infrastructure/monitoring/prometheus:/etc/prometheus:ro" \
  --entrypoint promtool prom/prometheus:v3.5.0 check rules /etc/prometheus/alerts.yml
docker run --rm -v "$PWD/infrastructure/monitoring/alertmanager:/etc/alertmanager:ro" \
  --entrypoint amtool prom/alertmanager:v0.28.1 check-config /etc/alertmanager/alertmanager.yml
```

---

## 5. Alert Runbook (`prometheus/alerts.yml`)

| Alert | Severity | Condition | Investigation & Mitigation |
| --- | --- | --- | --- |
| `TargetUnavailable` | `critical` | `up == 0` for `2m` | Check `docker compose ps` and container logs for the failing scrape target (`backend`, `postgres-exporter`, or `worker-exporter`). Verify `metrics_token` matches `cloudforge.monitoring.token` (>= 32 chars) if `backend` returns `403`. |
| `DatabaseUnavailable` | `critical` | `pg_up == 0` for `2m` | Verify PostgreSQL health (`pg_isready`), check `GET /api/health` (expected `503 DEGRADED` when DB is unreachable), inspect PostgreSQL logs and `MONITORING_DB_USER` credentials. |
| `BackendHighErrorRate` | `warning` | 5xx ratio > 5% over `5m` for `5m` | Filter structured backend logs by `requestId` and HTTP status `5xx`; check database pool saturation and external GitHub API errors. |
| `BackendHighLatency` | `warning` | API p95 > 1s over `5m` for `5m` | Inspect slow SQL queries, HikariCP wait times, or upstream GitHub API timeouts (`api-read-timeout-seconds`). |
| `ConnectionPoolSaturation` | `warning` | `hikaricp_connections_active / hikaricp_connections_max > 0.9` for `5m` | Inspect long-running DB transactions, check `maximum-pool-size` (`10` local / `5` Render), and confirm external HTTP calls are not holding open DB transactions. |
| `WorkerHeartbeatStale` | `critical` | `time() - cloudforge_worker_last_heartbeat_seconds > 90` for `2m` | Check whether the host worker process crashed, lost network connectivity to the control plane, or has an expired/revoked credential. Verify `WorkerRecoveryService` reclaimed expired leases. |
| `WorkerHeartbeatFailures` | `warning` | `increase(cloudforge_worker_heartbeat_failures_total[5m]) > 3` for `1m` | Inspect worker logs for HTTP `401`/`403`/`503` control-plane responses or TLS errors. |
| `PipelineFailures` | `warning` | `>= 3` `PIPELINE_RUN_FAILED` actions in `10m` for `1m` | Open CloudForge **Pipelines -> CloudForge Pipelines**, inspect failed stage attempt categories and sanitized logs, and check whether failure is infrastructure-related or user-configuration-related. |

---

## 6. Security Controls

- **Fail-closed metrics scraping:** `/actuator/prometheus` is excluded from default/Render profiles and requires a `>= 32`-character Bearer token verified with `MessageDigest.isEqual` on `GET` requests only.
- **No browser credentials:** Grafana credentials and Prometheus scrape tokens are never exposed to frontend JavaScript or committed to source control.
- **Resilience:** Monitoring container failures or absent metrics directories do not prevent the CloudForge backend or workers from serving requests or completing stages.
