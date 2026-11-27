# CloudForge Docker Infrastructure (`infrastructure/docker/`)

This directory contains shared Docker build and execution assets supporting CloudForge's local, CI, and Render deployments.

## Active Container Assets

| Path | Purpose |
| --- | --- |
| [`compose.yaml`](../../compose.yaml) | Local PostgreSQL 17 (`postgres:17-alpine`, persistent named volume `cloudforge_postgres_data`, localhost-only `127.0.0.1:5432`) and Spring Boot control-plane backend (`127.0.0.1:8080`). |
| [`compose.monitoring.yaml`](../../compose.monitoring.yaml) | Optional Phase 4 observability overlay adding Prometheus, Grafana (`127.0.0.1:3000`), Alertmanager, OpenTelemetry Collector, PostgreSQL exporter, and Worker textfile exporter. |
| [`backend/Dockerfile`](../../backend/Dockerfile) | Multi-stage Maven + non-root `eclipse-temurin:17-jre-alpine` backend runtime image. |
| [`frontend/Dockerfile`](../../frontend/Dockerfile) | Multi-stage Node 24 + patched Alpine `nginx` unprivileged SPA container (`USER nginx`, read-only filesystem compatible, `/api`/`/oauth2`/`/actuator` blocked with `404`). |
| [`Dockerfile.render`](../../Dockerfile.render) | Single-origin Render staging Docker build bundling Vite React assets into Spring Boot static resources (`node:24-alpine` -> `maven:3.9.9-eclipse-temurin-17-alpine` -> non-root `eclipse-temurin:17-jre-alpine` with `-XX:InitialRAMPercentage=20.0 -XX:MaxRAMPercentage=55.0`). |
| [`infrastructure/docker/maven-settings-render.xml`](maven-settings-render.xml) | Maven mirror configuration used by `Dockerfile.render` to avoid Maven Central rate-limiting on shared cloud build hosts. |
| [`infrastructure/docker/executor/Dockerfile`](executor/Dockerfile) | Minimal non-root executor container reference used with isolated Phase 3.3 worker operations. |

## Security Boundaries

- Neither `backend/Dockerfile` nor `Dockerfile.render` installs Docker CLI, BuildKit, `kubectl`, or kubeconfig files, and `compose.yaml` never mounts `/var/run/docker.sock` into the backend container.
- All runtime images drop root privileges (`USER cloudforge` or `USER nginx`).
- Untrusted multi-tenant repository builds remain outside standard Docker container isolation scope and require microVM/gVisor/Kata sandboxing before untrusted public production use.
