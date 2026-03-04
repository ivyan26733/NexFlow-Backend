-- Add payload and error_message columns to executions for concurrency-safe flow tracking.
-- Uses IF NOT EXISTS so it is safe to run against existing databases.

ALTER TABLE executions
    ADD COLUMN IF NOT EXISTS payload       TEXT,
    ADD COLUMN IF NOT EXISTS error_message TEXT;

