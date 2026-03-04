package com.nexflow.nexflow_backend.model.dto;

import com.nexflow.nexflow_backend.model.domain.BranchExecutionStatus;

import java.util.Map;

/**
 * Lightweight result object returned by a completed branch thread.
 * Passed from ForkNodeExecutor back to the JOIN merge step.
 */
public class BranchResult {

    private final String branchName;
    private final BranchExecutionStatus status;
    private final Map<String, Object> nex;
    private final long durationMs;
    private final String errorMessage;

    public BranchResult(String branchName,
                        BranchExecutionStatus status,
                        Map<String, Object> nex,
                        long durationMs,
                        String errorMessage) {
        this.branchName = branchName;
        this.status = status;
        this.nex = nex;
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
    }

    public static BranchResult success(String name, Map<String, Object> nex, long ms) {
        return new BranchResult(name, BranchExecutionStatus.SUCCESS, nex, ms, null);
    }

    public static BranchResult failure(String name, String error, long ms) {
        return new BranchResult(name, BranchExecutionStatus.FAILURE, null, ms, error);
    }

    public static BranchResult timeout(String name, long ms) {
        return new BranchResult(name, BranchExecutionStatus.TIMEOUT, null, ms,
                "Branch exceeded timeout limit");
    }

    public static BranchResult cancelled(String name) {
        return new BranchResult(name, BranchExecutionStatus.CANCELLED, null, 0, null);
    }

    public boolean isSuccess()   { return status == BranchExecutionStatus.SUCCESS; }
    public boolean isFailed()    { return status == BranchExecutionStatus.FAILURE; }
    public boolean isTimeout()   { return status == BranchExecutionStatus.TIMEOUT; }
    public boolean isCancelled() { return status == BranchExecutionStatus.CANCELLED; }

    public String getBranchName() {
        return branchName;
    }

    public BranchExecutionStatus getStatus() {
        return status;
    }

    public Map<String, Object> getNex() {
        return nex;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}

