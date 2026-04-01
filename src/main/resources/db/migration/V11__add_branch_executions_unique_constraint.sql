-- Ensure one row per branch per execution (execution_id + fork_node_id + branch_name).

ALTER TABLE branch_executions
    ADD CONSTRAINT branch_executions_execution_fork_branch_unique
    UNIQUE (execution_id, fork_node_id, branch_name);
