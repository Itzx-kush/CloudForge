# Docker Infrastructure (Planned Expansion)

## Phase 1 Status

In Phase 1, the active Docker definitions live alongside their respective services:

- Root orchestration: [`compose.yaml`](../../compose.yaml) (PostgreSQL 17 + Spring Boot backend)
- Backend container build: [`backend/Dockerfile`](../../backend/Dockerfile)

## Planned Future Additions

Future phases will place shared containerization assets in this directory, including:

- Production multi-stage frontend NGINX image (`frontend` static asset serving + reverse proxy)
- Isolated, rootless build-worker images with strict CPU/memory/network quotas for untrusted repository builds
- Container registry configuration and image signing/SBOM templates (Cosign, Syft, Trivy)
