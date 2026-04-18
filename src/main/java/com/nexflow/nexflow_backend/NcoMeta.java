package com.nexflow.nexflow_backend;

import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import com.nexflow.nexflow_backend.model.nco.LoopState;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class NcoMeta {
    private String flowId;
    private String executionId;
    /** UUID of the user who owns the flow being executed. Used to look up per-user LLM configs. */
    private UUID userId;
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
