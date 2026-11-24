package com.cloudforge.worker.executor;

import java.util.List;
import java.util.Map;

/**
 * Compile-time allowlist of fixed, version-controlled operations permitted inside
 * CloudForge Phase 3.3 isolated execution containers.
 *
 * <p>No user-supplied command string, repository script, branch name, or metadata value
 * is ever interpolated into container commands.</p>
 */
public final class AllowlistedOperationRegistry {

    private static final Map<String, List<String>> OPERATION_COMMANDS = Map.of(
            "ENV_VALIDATION", List.of(
                    "/bin/sh", "-eu", "-c",
                    "echo '[ENV_VALIDATION] Starting isolated container environment validation';"
                            + "UID_VAL=$(id -u); GID_VAL=$(id -g);"
                            + "echo \"[ENV_VALIDATION] uid=${UID_VAL} gid=${GID_VAL}\";"
                            + "if [ \"${UID_VAL}\" = \"0\" ]; then echo '[ENV_VALIDATION] ERROR: running as root' >&2; exit 11; fi;"
                            + "if touch /cf-ro-probe 2>/dev/null; then echo '[ENV_VALIDATION] ERROR: rootfs is writable' >&2; exit 12; fi;"
                            + "echo '[ENV_VALIDATION] Read-only root filesystem verified';"
                            + "echo 'ok' > /tmp/cf-tmp-probe && rm -f /tmp/cf-tmp-probe;"
                            + "echo '[ENV_VALIDATION] Bounded /tmp scratch verified';"
                            + "echo '[ENV_VALIDATION] Environment validation passed'"
            ),
            "CONTROLLED_SMOKE_TEST", List.of(
                    "/bin/sh", "-eu", "-c",
                    "echo '[CONTROLLED_SMOKE_TEST] Starting deterministic CloudForge smoke test';"
                            + "printf '[CONTROLLED_SMOKE_TEST] UTF-8 check: CloudForge — Phase 3.3 ✓\\n';"
                            + "echo '[CONTROLLED_SMOKE_TEST] STDERR stream check' >&2;"
                            + "CHECK_SUM=$(printf 'cloudforge-phase3-3-smoke' | sha256sum | cut -d' ' -f1);"
                            + "echo \"[CONTROLLED_SMOKE_TEST] deterministic_sha256=${CHECK_SUM}\";"
                            + "echo '[CONTROLLED_SMOKE_TEST] Smoke test passed'"
            ),
            "WORKER_DIAGNOSTICS", List.of(
                    "/bin/sh", "-eu", "-c",
                    "echo '[WORKER_DIAGNOSTICS] Starting isolated worker diagnostics';"
                            + "echo \"[WORKER_DIAGNOSTICS] kernel=$(uname -s) arch=$(uname -m) uid=$(id -u)\";"
                            + "echo \"[WORKER_DIAGNOSTICS] hostname=$(hostname)\";"
                            + "if [ -r /sys/fs/cgroup/memory.max ]; then echo \"[WORKER_DIAGNOSTICS] cgroup_memory_max=$(cat /sys/fs/cgroup/memory.max)\"; fi;"
                            + "if [ -r /sys/fs/cgroup/pids.max ]; then echo \"[WORKER_DIAGNOSTICS] cgroup_pids_max=$(cat /sys/fs/cgroup/pids.max)\"; fi;"
                            + "if [ -e /var/run/docker.sock ]; then echo '[WORKER_DIAGNOSTICS] ERROR: docker.sock exposed!' >&2; exit 13; fi;"
                            + "echo '[WORKER_DIAGNOSTICS] Verified no Docker socket present inside container';"
                            + "echo '[WORKER_DIAGNOSTICS] Diagnostics complete'"
            )
    );

    private AllowlistedOperationRegistry() {
    }

    public static boolean isSupported(String operation) {
        return operation != null && OPERATION_COMMANDS.containsKey(operation);
    }

    public static List<String> resolveFixedCommand(String operation) {
        List<String> cmd = operation != null ? OPERATION_COMMANDS.get(operation) : null;
        if (cmd == null) {
            throw new UnsupportedOperationException(
                    "Operation \"" + operation + "\" is not allowlisted for Phase 3.3 isolated execution"
            );
        }
        return cmd;
    }
}
