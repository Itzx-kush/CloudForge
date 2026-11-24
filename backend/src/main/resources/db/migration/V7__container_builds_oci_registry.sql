-- Phase 3.5 source worker registration. Existing workers preserve their adapter.
ALTER TABLE worker_nodes DROP CONSTRAINT ck_worker_nodes_adapter;
ALTER TABLE worker_nodes ADD CONSTRAINT ck_worker_nodes_adapter
    CHECK (adapter_type IN ('DOCKER_ISOLATED', 'MOCK_TEST', 'GITHUB_SOURCE'));

ALTER TABLE stage_execution_attempts ADD COLUMN source_archive_issued_at TIMESTAMP WITH TIME ZONE;
