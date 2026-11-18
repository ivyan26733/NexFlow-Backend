-- Files (uploads, chat attachments, FILE node output) and chat channel connections.
-- Needed when the server runs with spring.jpa.hibernate.ddl-auto=validate; with "update" Hibernate creates these itself.

CREATE TABLE IF NOT EXISTS stored_files (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL,
    name          VARCHAR(255) NOT NULL,
    mime_type     VARCHAR(160),
    size_bytes    BIGINT       NOT NULL,
    storage_key   VARCHAR(512) NOT NULL,
    source        VARCHAR(32)  NOT NULL,
    flow_id       UUID,
    execution_id  UUID,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_stored_files_user      ON stored_files (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_stored_files_execution ON stored_files (execution_id);

CREATE TABLE IF NOT EXISTS channel_connections (
    id                 UUID PRIMARY KEY,
    user_id            UUID         NOT NULL,
    platform           VARCHAR(32)  NOT NULL CHECK (platform IN ('SLACK', 'TELEGRAM', 'WHATSAPP', 'DISCORD')),
    label              VARCHAR(255) NOT NULL,
    flow_id            UUID,
    enabled            BOOLEAN      NOT NULL,
    webhook_key        VARCHAR(64)  NOT NULL UNIQUE,
    credentials        JSONB,
    settings           JSONB,
    event_count        BIGINT       NOT NULL DEFAULT 0,
    last_event_at      TIMESTAMP(6) WITH TIME ZONE,
    last_execution_id  UUID,
    last_error         VARCHAR(1000),
    created_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_channel_connections_user ON channel_connections (user_id);
CREATE INDEX IF NOT EXISTS idx_channel_connections_flow ON channel_connections (flow_id);

-- New node types FILE and MESSAGE (SchemaMaintenance also refreshes this on startup).
ALTER TABLE flow_nodes DROP CONSTRAINT IF EXISTS flow_nodes_node_type_check;
ALTER TABLE flow_nodes ADD CONSTRAINT flow_nodes_node_type_check CHECK (node_type IN (
    'START', 'NEXUS', 'SUB_FLOW', 'SCRIPT', 'VARIABLE', 'MAPPER', 'DECISION', 'LOOP', 'SUCCESS', 'FAILURE', 'AI',
    'FILE', 'MESSAGE', 'KAFKA_PRODUCER', 'KAFKA_CONSUMER', 'DELAY', 'TRANSFORM', 'FORK', 'JOIN'));
