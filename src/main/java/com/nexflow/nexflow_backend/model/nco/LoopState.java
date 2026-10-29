package com.nexflow.nexflow_backend.model.nco;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime state of one active LOOP node.
 *
 * {@code index} is the number of times the loop body has started so far (0 before the first pass),
 * which is what {{loop.index}} resolves to. {@code accumulated} collects the output of the last body
 * step of each pass. The state is discarded when the loop exits, so re-entering the loop
 * (e.g. a loop nested inside another loop) starts fresh.
 */
@Data
public class LoopState {
    /** Keep at most this many accumulated outputs to bound memory on long loops. */
    public static final int MAX_ACCUMULATED = 100;

    private final String loopNodeId;
    private int index = 0;
    private List<Object> accumulated = new ArrayList<>();

    public void accumulate(Object value) {
        if (accumulated.size() < MAX_ACCUMULATED) accumulated.add(value);
    }
}
