package com.nexflow.nexflow_backend;

import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class NcoMeta {
    private String flowId;
    private String executionId;
    private String currentNodeId;
    private Instant startedAt;
    private Instant completedAt;
    private ExecutionStatus status;
}
