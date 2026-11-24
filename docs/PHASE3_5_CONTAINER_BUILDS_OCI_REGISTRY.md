# CloudForge Phase 3.5 source acquisition

This draft delivers GitHub source preparation for Phase 3.5. Container image construction, registry publishing, and artifact persistence are still open. The runbook below covers the source stage.

## Control and execution planes

1. An OWNER or DEVELOPER queues a pipeline with a `SOURCE_PREP` stage configured for `GITHUB_SOURCE_ACQUIRE`. The existing Phase 3.4 OWNER requirement applies to runs with encrypted secret bindings on other stages.
2. The control plane checks workspace membership, project and repository linkage, installation status, and the operator's repository allowlist. It resolves the selected ref against the immutable GitHub repository ID and stores the 40-character commit SHA on the run and its definition snapshot. A supplied SHA acts as a precondition on that resolution. Idempotent replay returns the original pinned run.
3. The existing scheduler leases the stage to an operator-approved `GITHUB_SOURCE` worker. That adapter advertises only source acquisition and accepts one concurrent stage.
4. After acknowledging the running attempt, the worker POSTs its lease token and fencing generation to `/api/internal/workers/{workerId}/attempts/{attemptId}/source-archive`. The control plane verifies HTTPS, the current worker identity, cancellation state, and lease. It grants one archive URL per attempt and sends `Cache-Control: no-store`.
5. The worker downloads the pinned ZIP from `codeload.github.com`, validates and extracts it into a private temporary directory, and removes the ZIP and extracted source before reporting success. Source contents and credentials are excluded from logs and run snapshots. The pipeline and worker attempt retain the standard lifecycle and existing audit events.

### GitHub permissions and credential handling

Set the GitHub App repository permission **Contents → Read-only** and approve the permission change on the test installation. The source API requests a fresh installation token with Metadata and Contents read permissions restricted to the selected repository ID. Existing Metadata and Actions read scopes continue serving their existing endpoints. The control plane holds the installation token in memory and uses it only with GitHub's API. The worker receives GitHub's scoped archive URL over HTTPS, keeps it in memory, and sends no installation token or worker authentication header to codeload. GitHub determines the URL's expiration, so a previously issued URL may outlive a cancelled attempt.

### Worker bounds and cleanup

The worker limits the downloaded ZIP to 32 MiB, extracted file contents to 128 MiB, and the archive to 12,000 entries. It checks the ZIP directory before indexing and rejects unsafe paths, duplicate entries, malformed data, links, special files, and elevated file modes. Extraction creates fresh files using local permissions. Git submodules remain uninitialized. CloudForge performs zero client-side Git LFS expansion. Disable the repository setting to include LFS objects in archives if LFS objects must remain pointers.

The worker checks cancellation and the stage deadline during download and extraction. A blocked network read may take up to the socket timeout to return. Temporary files are removed when the attempt exits normally or handles a cancellation or error. A worker host that loses power may leave its private temporary directory behind. Standard worker lease recovery governs lost attempts.

### Database

`V7__container_builds_oci_registry.sql` changes two existing tables. It adds the `GITHUB_SOURCE` adapter to the `worker_nodes` constraint and adds a nullable archive issuance timestamp to `stage_execution_attempts`. There are 25 application tables. The migration adds zero tables.

## Local verification

1. Use a development GitHub App installation and link a controlled fixture repository to a CloudForge project. Approve Contents read access and note the numeric GitHub repository ID.
2. Start the local control plane behind a trusted HTTPS endpoint. Set `CLOUDFORGE_WORKERS_ENABLED=true`, `CLOUDFORGE_SOURCE_ACQUISITION_ENABLED=true`, and the comma-separated `CLOUDFORGE_SOURCE_ALLOWED_REPOSITORY_IDS` and `CLOUDFORGE_SOURCE_ALLOWED_WORKER_IDS`. Provision the dedicated worker credential using the existing Phase 3.3 workflow.
3. Launch the standalone worker with its matching `CLOUDFORGE_WORKER_ID` and `CLOUDFORGE_WORKER_TOKEN`, the HTTPS `CLOUDFORGE_CONTROL_PLANE_URL`, and `CLOUDFORGE_WORKER_SOURCE_ONLY=true`. The worker requires Java 17 and network access to GitHub codeload. The default concurrency is one.
4. In Pipelines, create a definition for the linked project with the source operation. Queue the default branch, enter a branch name, or select a tag using `refs/tags/<tag>`. Open the run and compare its SHA to the chosen ref at queue time.
5. Inspect the attempt for the archive verification and cleanup message. Queue again after moving the branch and confirm the original run still displays its initial commit. Exercise cancellation and verify that a stale lease cannot receive an archive URL.

## Verification scope and remaining work

Backend tests exercise scoped GitHub API requests, immutable pinning, capability gating, HTTPS and lease enforcement, and one grant per attempt. GitHub App responses in these tests use synthetic data. Worker tests extract ZIP payloads and exercise archive restrictions. Frontend tests cover queue validation with an optional full SHA. Render staging retains its execution-disabled defaults.

Local verification used Java 17, PostgreSQL 17.12, and the existing frontend toolchain. Backend results were 74 passed including six PostgreSQL tests. Worker results were 16 passed and two existing Docker tests skipped because the daemon was unavailable. All 28 frontend tests passed. Backend, worker, and frontend packages built, and Compose configuration validated. Flyway applied V1–V7 on PostgreSQL 17.12. The worker extracted 407 files totaling 2,822,552 bytes from the public CloudForge ZIP for commit `85ac1a9ee1bf8f6ce5a550b4135fb5406a20c39b`. The public ZIP check covers archive processing. Private GitHub App access was covered with synthetic responses.

Phase 3.5 still needs Dockerfile and build context validation, an isolated BuildKit executor with enforced limits, registry lifecycle and encrypted credential references, real publishing and digest verification, and persistent artifact management. Phase 3.6 covers Kubernetes orchestration. Phase 3.7 covers deployment history and rollback. Phase 4 covers observability and DevSecOps.
