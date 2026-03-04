package com.nexflow.nexflow_backend.model.domain;

/**
 * Lifecycle states for a single parallel branch inside a FORK–JOIN pair.
 * Mirrors ExecutionStatus but scoped to one branch, not the whole flow.
 */
public enum BranchExecutionStatus {

    /** Branch created by FORK, waiting for a thread to pick it up. */
    PENDING,

    /** Branch is actively executing its node sequence. */
    RUNNING,

    /** All nodes in this branch completed without error. */
    SUCCESS,

    /** At least one node in this branch returned FAILURE. */
    FAILURE,

    /** Branch did not complete within the FORK timeout window. */
    TIMEOUT,

    /** Branch was cancelled because WAIT_FIRST / WAIT_N threshold was met. */
    CANCELLED
}

