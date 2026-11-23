-- ============================================================================
-- CloudForge Phase 2 — V2: GitHub Webhook Delivery Idempotency Tracking
-- ============================================================================

CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY,
    delivery_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    action VARCHAR(64),
    github_installation_id BIGINT,
    payload_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary VARCHAR(500),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_webhook_deliveries_delivery_id UNIQUE (delivery_id),
    CONSTRAINT ck_webhook_deliveries_status CHECK (status IN ('PROCESSED', 'IGNORED', 'FAILED'))
);

CREATE INDEX idx_webhook_deliveries_event_type ON webhook_deliveries (event_type);
CREATE INDEX idx_webhook_deliveries_installation ON webhook_deliveries (github_installation_id);
CREATE INDEX idx_webhook_deliveries_received_at ON webhook_deliveries (received_at DESC);
