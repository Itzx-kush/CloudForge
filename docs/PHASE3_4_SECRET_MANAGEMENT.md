# CloudForge Phase 3.4 — Encrypted Secret Management, Key Management & Secure Worker Delivery

> **Status:** Implemented (Backend secret management, AES-256-GCM envelope cryptography, `env-keyring` provider, DEK-only master key rotation, pipeline secret bindings & queue-time version pinning, lease-bound worker secret delivery, multi-layer secret redaction, and frontend write-only vault UI). Worker secret delivery and operator key rotation default to **disabled** (`false`) until explicitly configured; OCI registry pushes (Phase 3.5), Kubernetes rollouts (Phase 3.6), and rollback/DevSecOps gates (Phase 3.7–4) remain planned.

---

## 1. Security Model & Scope Separation

CloudForge Phase 3.4 enforces strict separation between three configuration planes:

1. **Non-Secret Environment Configuration (`project_environments.config_variables_json`):**
   Continues rejecting secret-like keys (`*SECRET*`, `*PASSWORD*`, `*TOKEN*`, `*API_KEY*`, etc.) and credential patterns (`-----BEGIN ...`, `ghp_`, etc.) with HTTP `400 Bad Request`.
2. **Write-Only Encrypted Environment Secrets (`environment_secrets` + `environment_secret_versions`):**
   AES-256-GCM envelope-encrypted secrets scoped to a single workspace, project, and environment (`DEVELOPMENT`, `STAGING`, `PRODUCTION`). User REST APIs return **cryptographic metadata only** and enforce `Cache-Control: no-store`.
3. **Internal Lease-Bound Worker Delivery (`/api/internal/workers/{workerId}/attempts/{attemptId}/secrets`):**
   Short-lived secret retrieval reserved exclusively for authenticated trusted workers holding an active, unexpired stage attempt lease and matching fencing generation. Never accessible to browser OAuth sessions or ordinary workspace users.

---

## 2. Cryptographic Format (v1 Envelope Encryption)

Every secret version is encrypted via `SecretCryptoService` (`com.cloudforge.backend.secrets.crypto`):

- **Cipher:** `AES/GCM/NoPadding` (256-bit random DEK generated per secret version via `SecureRandom`).
- **Nonce:** Fresh 96-bit (`12`-byte) CSPRNG nonce per secret version (`ck_env_secret_versions_nonce_len CHECK (octet_length(nonce) = 12)` in PostgreSQL/H2).
- **Authentication Tag:** 128-bit GCM tag (`GCMParameterSpec(128, nonce)`).
- **Secret Value AAD (`SecretAadBuilder.buildSecretValueAad`):**
  Deterministic length-prefixed encoding binding ciphertext and tag to:
  `(formatVersion=1, workspaceId, projectId, environmentId, secretId, versionNumber)`.
  Notice `kekId` is deliberately excluded from `secretAad` so master key rotation can rewrap `wrapped_dek` without decrypting `ciphertext`.
- **Wrapped DEK Envelope (`wrapped_dek` BYTEA):**
  `[ 0x01 format byte || 12-byte wrapNonce || AES-256-GCM(KEK, wrapNonce, 32-byte DEK, wrapAad) ]`,
  where `wrapAad` binds `(formatVersion=1, workspaceId, projectId, environmentId, secretId, versionNumber, kekId)`.

Tampering with ciphertext, authentication tags, wrapped DEKs, nonces, format versions, or moving ciphertext/DEKs across workspaces, projects, environments, secrets, or versions fails closed (`SecretCryptoException` -> HTTP 500 opaque safe error).

---

## 3. Master Key Provider & Rotation (`SecretKeyProvider`)

- **Interface:** `SecretKeyProvider` (`com.cloudforge.backend.secrets.crypto`) isolates envelope operations from key storage so future external KMS integrations (AWS KMS, GCP KMS, Azure Key Vault, HashiCorp Vault) drop in behind `wrapDataEncryptionKey`, `wrapDataEncryptionKeyWithActiveKey`, and `unwrapDataEncryptionKey`.
- **Local Development / Staging Provider (`EnvKeyringSecretKeyProvider`, provider `env-keyring`):**
  - Reads externally provisioned keys from `CLOUDFORGE_SECRETS_KEYRING` (`keyId1:base64_256bit_key,keyId2:base64_256bit_key`) and selects `CLOUDFORGE_SECRETS_ACTIVE_KEY_ID`.
  - Validates exact 256-bit (32-byte) non-zero key length per entry.
  - Rejects placeholders, short keys, duplicate IDs, missing active key IDs, or unsupported provider names by entering a fail-closed `UNAVAILABLE` state (`HTTP 503 SecretManagementUnavailableException` on encryption/decryption).
  - Never auto-generates keys on startup, never persists master keys in PostgreSQL, and never logs key material.
- **DEK-Only Master Key Rotation (`SecretKeyRotationService` + `/api/internal/operator/secrets/**`):**
  - Authenticated strictly via stateless `OperatorTokenAuthenticationFilter` (`ROLE_SECRET_OPERATOR`, constant-time SHA-256 comparison against `CLOUDFORGE_SECRETS_OPERATOR_ROTATION_TOKEN`, disabled by default).
  - Rewraps `wrapped_dek` in bounded batches (`<= 200` rows per transaction) using `PESSIMISTIC_WRITE` locks and cursor pagination without decrypting secret plaintext or mutating `ciphertext`/`nonce`.
  - Skips already-rewrapped records idempotently and fails closed per row without corrupting existing wrapped keys if a historical KEK is missing.

### KEK Rotation Operator Runbook

```powershell
# 1. Generate a new 256-bit AES key (do NOT remove old key yet)
openssl rand -base64 32

# 2. Add the new key alongside historical key(s) and update active key ID:
#    CLOUDFORGE_SECRETS_ACTIVE_KEY_ID=kek-v2
#    CLOUDFORGE_SECRETS_KEYRING=kek-v1:<old_base64>,kek-v2:<new_base64>
#    CLOUDFORGE_SECRETS_OPERATOR_ROTATION_ENABLED=true
#    CLOUDFORGE_SECRETS_OPERATOR_ROTATION_TOKEN=<high_entropy_operator_token>

# 3. Preview candidate secret versions without mutating (dryRun=true)
curl -X POST http://127.0.0.1:8080/api/internal/operator/secrets/keys/rotate \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"batchSize":50,"dryRun":true,"initiatedBy":"sec-ops"}'

# 4. Execute idempotent DEK-only batch rewrapping
curl -X POST http://127.0.0.1:8080/api/internal/operator/secrets/keys/rotate \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"batchSize":50,"dryRun":false,"initiatedBy":"sec-ops"}'

# 5. Confirm zero versions remain on older KEKs before retiring kek-v1 from CLOUDFORGE_SECRETS_KEYRING
curl -H "Authorization: Bearer $OPERATOR_TOKEN" \
  http://127.0.0.1:8080/api/internal/operator/secrets/keys/status
```

---

## 4. Database Schema (`V6__encrypted_secret_management.sql`)

Non-destructive migration preserving `V1`–`V5` untouched:

- `environment_secrets`: Workspace/project/environment-scoped secret metadata with `name_lock_key` uniqueness (`secret_name` when `ACTIVE`/`REVOKED`, `'DELETED:' || id` on soft delete).
- `environment_secret_versions`: Immutable ciphertext (`BYTEA`), 12-byte nonce check, `wrapped_dek`, `kek_id`, `status` (`ACTIVE`/`REVOKED`), composite scope FK to `environment_secrets`.
- `pipeline_run_secret_references`: Immutable queue-time version pins (`secret_version_id`, `pinned_version_number`, `delivery_alias`, `delivery_count`).
- `secret_key_rotation_runs`: Master key rotation ledger (`RUNNING`, `SUCCEEDED`, `COMPLETED_WITH_ERRORS`, `FAILED`).
- Alterations to `pipeline_definitions`, `pipeline_stage_definitions`, `pipeline_runs`, `pipeline_stage_runs`, and `stage_execution_attempts` (`target_environment_id`, `secret_bindings_json`, `has_secret_bindings`, `secret_delivery_count`, `last_secret_delivery_at`).

---

## 5. Authorization Matrix (Conservative RBAC Policy)

| Operation | `OWNER` | `DEVELOPER` | `VIEWER` | Non-Member / Worker |
| --- | --- | --- | --- | --- |
| Secret subsystem availability status (`GET .../secrets/status`) | Allowed (`activeKeyId` shown) | Read booleans only | Read booleans only | `403` |
| Secret metadata list / version history | Allowed | `403` (audited) | `403` (audited) | `403` |
| Create / Rotate / Revoke / Delete secret | Allowed (CSRF) | `403` (audited) | `403` (audited) | `403` |
| Define/update pipeline secret bindings | Allowed (CSRF) | `403` | `403` | `403` |
| Queue pipeline run **without** secret bindings | Allowed (CSRF) | Allowed (CSRF) | `403` | `403` |
| Queue pipeline run **with** secret bindings | Allowed (CSRF) | `403` (audited) | `403` (audited) | `403` |
| Lease-bound worker secret fetch (`POST .../attempts/{id}/secrets`) | `401` | `401` | `401` | Worker only (active lease + fence + HTTPS/opt-in) |
| Master key rewrap (`POST /api/internal/operator/secrets/keys/rotate`) | `401` | `401` | `401` | Operator token only (`ROLE_SECRET_OPERATOR`) |

---

## 6. Pipeline Bindings, Version Pinning & Worker Delivery

1. **Stage Eligibility Guard:** Only `SOURCE_PREP` and `TEST` stages configured with an allowlisted operation (`ENV_VALIDATION`, `CONTROLLED_SMOKE_TEST`, `WORKER_DIAGNOSTICS`) may bind secrets. All bound secrets in a pipeline definition must reside in a single project `targetEnvironmentId`.
2. **Queue-Time Version Pinning:** When queueing a run, `PipelineOrchestrationService` resolves active secret versions into `pipeline_run_secret_references` **without decrypting**. Post-queue rotations do not mutate already-queued runs; post-queue revocations immediately block delivery fail-closed.
3. **Lease-Bound Worker Delivery:** `SecretDeliveryService` enforces 12 preconditions (`workers.enabled`, `workerDeliveryEnabled`, active `SecretKeyProvider`, HTTPS or loopback opt-in, worker identity + active node, attempt ownership, active lease & unexpired TTL, matching `leaseToken`, matching `fencingGeneration`, non-cancelling/non-terminal state, approved operation, active non-revoked secret & version, bounded `maxDeliveriesPerAttempt`).
4. **Worker Isolation & Multi-Layer Redaction:**
   - Workers fetch ephemeral secrets strictly into memory over the lease-bound endpoint and clear them in `finally`.
   - `DockerIsolatedExecutionAdapter` passes secrets via ephemeral stdin pipe only (`-i`) — never CLI arguments, `--env` flags, `--label`, `-v` host bind mounts, or image layers.
   - Both `WorkerSecretRedactor` (execution plane) and `LogSanitizer` + `AttemptSecretRedactionService` (control plane) strip raw, multiline segment, Base64, Base64URL, URL-encoded (`+`/`%20`), and cross-chunk boundary-spanning secret fragments before persisting logs, summaries, or `resourceUsage`.

---

## 7. Production / Render Safety & Audit Trail

- Defaults across `application.yml`, `application-render.yml`, and `compose.yaml` keep `CLOUDFORGE_SECRETS_ENABLED=false`, `CLOUDFORGE_SECRETS_WORKER_DELIVERY_ENABLED=false`, `CLOUDFORGE_SECRETS_ALLOW_INSECURE_LOCAL_DELIVERY=false`, and `CLOUDFORGE_SECRETS_OPERATOR_ROTATION_ENABLED=false`.
- `application-render.yml` explicitly locks `worker-delivery-enabled: false` and `allow-insecure-local-delivery: false`.
- Public `/api/health` never exposes keys, key counts, keyring strings, or secret counts.
- `SecretAuditService` emits non-sensitive `SECRET_CREATED`, `SECRET_ROTATED`, `SECRET_REVOKED`, `SECRET_DELETED`, `SECRET_BINDING_UPDATED`, `SECRET_ACCESS_GRANTED`, `SECRET_ACCESS_DENIED` (cooldown rate-limited), `SECRET_KEY_REWRAPPED`, and `SECRET_KEY_ROTATION_RUN` events.
