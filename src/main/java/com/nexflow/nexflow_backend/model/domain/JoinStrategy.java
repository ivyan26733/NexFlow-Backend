package com.nexflow.nexflow_backend.model.domain;

/**
 * Controls how the JOIN node decides when to stop waiting for branches.
 */
public enum JoinStrategy {

    /**
     * Default. Wait for every branch to finish before merging.
     * Use when downstream nodes need all branch results.
     */
    WAIT_ALL,

    /**
     * Continue as soon as the first branch completes.
     * Remaining branches are cancelled.
     */
    WAIT_FIRST,

    /**
     * Continue when N branches have completed (quorum).
     * Remaining branches beyond the threshold are cancelled.
     */
    WAIT_N
}

