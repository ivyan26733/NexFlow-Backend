package com.nexflow.nexflow_backend;

public enum EdgeCondition {
    SUCCESS,   // follow this edge when source node succeeds
    FAILURE,   // follow this edge when source node fails
    CONTINUE,  // used by LOOP node to route back to loop body
    DEFAULT,   // always follow (for non-branching nodes)
    CUSTOM     // follow based on a custom expression
}
