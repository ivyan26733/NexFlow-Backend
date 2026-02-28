package com.nexflow.nexflow_backend;

import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import com.nexflow.nexflow_backend.model.nco.LoopState;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class NcoMeta {
    private String flowId;
    private String executionId;
    private String currentNodeId;
    private Instant startedAt;
    private Instant completedAt;
    private ExecutionStatus status;
    /** Set when execution is stopped due to loop detection or max steps (no process kill). */
    private String errorMessage;

    /** Per-LOOP-node state keyed by loop node id. Serialised in nco_snapshot. */
    @Builder.Default
    private Map<String, LoopState> loopStates = new HashMap<>();

    /** Set by engine at start: for each LOOP node id, true if it has at least one outgoing CONTINUE edge. */
    @Builder.Default
    private Map<String, Boolean> loopNodeHasContinueEdge = new HashMap<>();
}
