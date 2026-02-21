package com.nexflow.nexflow_backend;

public enum EdgeCondition {
    SUCCESS,   // follow this edge when source node succeeds
    FAILURE,   // follow this edge when source node fails
    DEFAULT,   // always follow (for non-branching nodes)
    CUSTOM     // follow based on a custom expression
}
