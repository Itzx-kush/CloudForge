# Monitoring & Observability Stack (Planned — Phase 4)

## Phase 1 Status

Phase 1 provides foundational backend observability through:

- `GET /api/health` (live application + PostgreSQL connectivity probe)
- Spring Boot Actuator (`/actuator/health`, `/actuator/info`)
- Structured application logs with request correlation IDs

## Planned Architecture

Phase 4 will introduce full observability configuration in this directory:

- **Prometheus**: Scrape configurations for Spring Boot Micrometer metrics (`/actuator/prometheus`), PostgreSQL exporter, and Kubernetes cluster metrics
- **Grafana**: Provisioned datasources and dashboards for API latency, error rates, database pool health, pipeline durations, and deployment status
- **Alerting**: Threshold-based alert rules for service degradation, failed rollouts, and database saturation
