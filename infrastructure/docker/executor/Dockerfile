# ==============================================================================
# CloudForge Phase 3.3 — Hardened Non-Root Executor Reference Image
# ==============================================================================
# Minimal Alpine 3.20 runtime used for strictly allowlisted isolated operations
# (ENV_VALIDATION, CONTROLLED_SMOKE_TEST, WORKER_DIAGNOSTICS).
# Never includes Docker CLI, host sockets, credentials, or root privileges.

FROM alpine:3.20

RUN addgroup -S -g 65534 cfexec 2>/dev/null || true \
    && adduser -S -D -H -u 65534 -G cfexec cfexec 2>/dev/null || true

USER 65534:65534
WORKDIR /tmp
ENTRYPOINT ["/bin/sh"]
