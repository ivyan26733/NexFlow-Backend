package com.nexflow.nexflow_backend.model.nco;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-LOOP-node state: index, accumulated outputs from each iteration, max iterations.
 * Keyed by loop node id in NcoMeta.loopStates so multiple LOOP nodes do not interfere.
 */
@Data
public class LoopState {
    private String loopNodeId;
    private int index = 0;
    private List<Object> accumulated = new ArrayList<>();
    private int maxIterations = 100;

    public LoopState() {}

    public LoopState(String loopNodeId) {
        this.loopNodeId = loopNodeId;
    }
}
