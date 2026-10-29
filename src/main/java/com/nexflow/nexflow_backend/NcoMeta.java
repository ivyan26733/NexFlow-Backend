package com.nexflow.nexflow_backend;

import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class NcoMeta {
    private String flowId;
    private String executionId;
    /** UUID of the user who owns the flow being executed. Used to look up per-user LLM configs. */
    private UUID userId;
    private Instant startedAt;
    private Instant completedAt;
    private ExecutionStatus status;
    /** Plain-language reason the run failed (null on success). */
    private String errorMessage;
    /** Technical detail behind {@link #errorMessage}, for developers. */
    private String errorDetail;
}
