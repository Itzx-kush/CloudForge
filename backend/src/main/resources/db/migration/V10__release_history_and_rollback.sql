ALTER TABLE kubernetes_deployment_operations ADD COLUMN revision_number BIGINT;
ALTER TABLE kubernetes_deployment_operations ADD COLUMN operation_kind VARCHAR(16) DEFAULT 'DEPLOYMENT' NOT NULL;
ALTER TABLE kubernetes_deployment_operations ADD COLUMN restored_release_id UUID REFERENCES kubernetes_deployment_operations(id);
ALTER TABLE kubernetes_deployment_operations ADD COLUMN expected_operation_id UUID REFERENCES kubernetes_deployment_operations(id);
ALTER TABLE kubernetes_deployment_operations ADD COLUMN guard_observation_id UUID REFERENCES kubernetes_deployment_operations(id);
ALTER TABLE kubernetes_deployment_operations ADD COLUMN operation_reason VARCHAR(128);

UPDATE kubernetes_deployment_operations
SET revision_number = ranked.revision
FROM (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY cluster_connection_id, namespace_name, deployment_name
        ORDER BY created_at, id
    ) AS revision
    FROM kubernetes_deployment_operations
) AS ranked
WHERE kubernetes_deployment_operations.id = ranked.id;

ALTER TABLE kubernetes_deployment_operations ADD CONSTRAINT uq_k8s_release_revision
    UNIQUE (cluster_connection_id, namespace_name, deployment_name, revision_number);
ALTER TABLE kubernetes_deployment_operations ADD CONSTRAINT ck_k8s_release_operation_kind CHECK (
    (operation_kind = 'DEPLOYMENT' AND revision_number > 0 AND revision_number IS NOT NULL
        AND restored_release_id IS NULL AND expected_operation_id IS NULL)
    OR (operation_kind = 'ROLLBACK' AND revision_number > 0 AND revision_number IS NOT NULL
        AND restored_release_id IS NOT NULL AND expected_operation_id IS NOT NULL AND operation_reason IS NOT NULL
        AND guard_observation_id IS NOT NULL)
    OR (operation_kind = 'RECONCILIATION' AND revision_number IS NULL
        AND restored_release_id IS NOT NULL AND expected_operation_id IS NOT NULL AND operation_reason IS NOT NULL)
);

CREATE INDEX idx_k8s_release_history ON kubernetes_deployment_operations
    (workspace_id, revision_number DESC, created_at DESC, id DESC);

UPDATE oci_image_artifacts
SET retention_policy = 'REFERENCED_PROTECTED'
WHERE EXISTS (
    SELECT 1 FROM kubernetes_deployment_operations op
    WHERE op.oci_artifact_id = oci_image_artifacts.id
);
