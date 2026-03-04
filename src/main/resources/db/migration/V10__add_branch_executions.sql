-- Parallel branch execution tracking for FORK/JOIN feature.

CREATE TABLE IF NOT EXISTS branch_executions (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id  UUID         NOT NULL,
    fork_node_id  UUID         NOT NULL,
    branch_name   VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    nex_snapshot  TEXT,
    started_at    TIMESTAMP,
    completed_at  TIMESTAMP,
    duration_ms   BIGINT,
    error_message TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_branch_exec_execution_id
    ON branch_executions (execution_id);

CREATE INDEX IF NOT EXISTS idx_branch_exec_fork_node_id
    ON branch_executions (fork_node_id);

CREATE INDEX IF NOT EXISTS idx_branch_exec_exec_fork
    ON branch_executions (execution_id, fork_node_id);

