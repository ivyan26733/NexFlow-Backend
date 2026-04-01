-- Per-node execution records for branch nodes (input/output visible in transaction detail).

CREATE TABLE IF NOT EXISTS node_executions (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id  UUID         NOT NULL,
    node_id       UUID         NOT NULL,
    node_label    VARCHAR(255) NOT NULL,
    node_type     VARCHAR(50)  NOT NULL,
    branch_name   VARCHAR(100),
    status        VARCHAR(20)  NOT NULL,
    input_nex     TEXT,
    output_nex    TEXT,
    duration_ms   BIGINT,
    error_message TEXT,
    started_at    TIMESTAMP    NOT NULL,
    finished_at   TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_node_exec_execution_id
    ON node_executions (execution_id);

CREATE INDEX IF NOT EXISTS idx_node_exec_execution_branch
    ON node_executions (execution_id, branch_name);
