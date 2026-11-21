package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The small columns of a run, for lists and counts. Reading these instead of the full entity keeps the
 * payload and nco_snapshot (several MB per data-heavy run) out of memory.
 */
public interface ExecutionRow {
    UUID getId();
    UUID getFlowId();
    ExecutionStatus getStatus();
    String getTriggeredBy();
    Instant getStartedAt();
    Instant getCompletedAt();
    String getErrorMessage();

    static ExecutionRow of(Execution e) {
        return new Row(e.getId(), e.getFlowId(), e.getStatus(), e.getTriggeredBy(), e.getStartedAt(), e.getCompletedAt(), e.getErrorMessage());
    }

    record Row(UUID getId, UUID getFlowId, ExecutionStatus getStatus, String getTriggeredBy,
               Instant getStartedAt, Instant getCompletedAt, String getErrorMessage) implements ExecutionRow {}
}
