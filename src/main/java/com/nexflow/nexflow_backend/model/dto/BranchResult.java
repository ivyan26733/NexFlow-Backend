package com.nexflow.nexflow_backend.model.dto;

import com.nexflow.nexflow_backend.model.domain.BranchExecutionStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight result object returned by a completed branch thread.
 * Passed from ForkNodeExecutor back to the JOIN merge step.
 * Failure path always carries an empty nex (never null) so merge logic is safe.
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
        this.nex = nex != null ? nex : new LinkedHashMap<>();
        this.durationMs = durationMs;
        this.errorMessage = errorMessage;
    }

    public static BranchResult success(String name, Map<String, Object> nex, long ms) {
        return new BranchResult(name, BranchExecutionStatus.SUCCESS, nex, ms, null);
    }

    /** Failure path — NEVER throws; returns a result with empty nex so merge can proceed when onBranchFailure=CONTINUE. */
    public static BranchResult failure(String name, String error, long ms) {
        return new BranchResult(name, BranchExecutionStatus.FAILURE, new LinkedHashMap<>(), ms, error);
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
    /** True if branch failed or timed out (used for onBranchFailure policy). Cancelled branches are not considered failure. */
    public boolean isFailure()   { return status == BranchExecutionStatus.FAILURE || status == BranchExecutionStatus.TIMEOUT; }
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

