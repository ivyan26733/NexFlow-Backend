package com.nexflow.nexflow_backend.model.dto;

import com.nexflow.nexflow_backend.model.domain.BranchExecutionStatus;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Result of one FORK branch.
 *
 * @param nex       the branch's nex after it ran (empty unless the branch succeeded)
 * @param joins     JOIN nodes the branch reached; the main path continues from these
 */
public record BranchResult(
        String branchName,
        BranchExecutionStatus status,
        Map<String, Object> nex,
        long durationMs,
        String errorMessage,
        Set<UUID> joins
) {
    public static BranchResult success(String name, Map<String, Object> nex, long ms, Set<UUID> joins) {
        return new BranchResult(name, BranchExecutionStatus.SUCCESS, nex, ms, null, joins);
    }

    public static BranchResult failure(String name, String error, long ms) {
        return new BranchResult(name, BranchExecutionStatus.FAILURE, Map.of(), ms, error, Set.of());
    }

    public static BranchResult timeout(String name, long ms) {
        return new BranchResult(name, BranchExecutionStatus.TIMEOUT, Map.of(), ms,
                "This branch didn't finish within the time limit.", Set.of());
    }

    public static BranchResult cancelled(String name) {
        return new BranchResult(name, BranchExecutionStatus.CANCELLED, Map.of(), 0, null, Set.of());
    }

    public boolean isSuccess() {
        return status == BranchExecutionStatus.SUCCESS;
    }

    /** Failed or timed out. Cancelled branches (stopped because enough others succeeded) are not failures. */
    public boolean isFailure() {
        return status == BranchExecutionStatus.FAILURE || status == BranchExecutionStatus.TIMEOUT;
    }
}
