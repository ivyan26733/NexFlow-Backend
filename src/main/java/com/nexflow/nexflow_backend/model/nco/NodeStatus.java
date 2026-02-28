package com.nexflow.nexflow_backend.model.nco;

public enum NodeStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILURE,
    CONTINUE,  // LOOP node: keep looping (follow CONTINUE edge)
    SKIPPED,
    RETRYING
}
