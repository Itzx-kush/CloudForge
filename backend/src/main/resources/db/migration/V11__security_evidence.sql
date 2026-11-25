CREATE TABLE artifact_security_evidence (
    id UUID PRIMARY KEY,
    artifact_id UUID NOT NULL REFERENCES oci_image_artifacts(id),
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    assessed_at BIGINT NOT NULL,
    critical_count INTEGER NOT NULL CHECK (critical_count >= 0),
    high_count INTEGER NOT NULL CHECK (high_count >= 0),
    sbom_sha256 VARCHAR(64) NOT NULL,
    signer_sha256 VARCHAR(64) NOT NULL,
    evidence_sha256 VARCHAR(64) NOT NULL UNIQUE,
    UNIQUE (artifact_id, assessed_at)
);
CREATE INDEX idx_security_evidence_workspace ON artifact_security_evidence(workspace_id, assessed_at DESC);
