package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks a single parallel branch spawned by a FORK node.
 * One row per branch per execution — e.g. 3 branches = 3 rows.
 */
@Entity
@Table(
    name = "branch_executions",
    indexes = {
        @Index(name = "idx_branch_exec_execution_id", columnList = "execution_id"),
        @Index(name = "idx_branch_exec_fork_node_id", columnList = "fork_node_id"),
        @Index(name = "idx_branch_exec_exec_fork", columnList = "execution_id, fork_node_id")
    }
)
public class BranchExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The parent flow execution this branch belongs to. */
    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    /** The FORK node that spawned this branch. Used by JOIN to find its siblings. */
    @Column(name = "fork_node_id", nullable = false)
    private UUID forkNodeId;

    /** Human-readable branch identifier set in FORK config, e.g. "branchA". */
    @Column(name = "branch_name", nullable = false, length = 100)
    private String branchName;

    /** Current state of this branch. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BranchExecutionStatus status = BranchExecutionStatus.PENDING;

    /**
     * The nex snapshot produced by this branch after it completes.
     * Stored as JSON so JOIN can merge it into the main nex.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nex_snapshot", columnDefinition = "text")
    private Map<String, Object> nexSnapshot;

    /** Wall-clock time when this branch started executing. */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** Wall-clock time when this branch finished (success, failure, or timeout). */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** How long this branch ran in milliseconds. Populated on completion. */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** Error message if this branch failed. Null on success. */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public UUID getId() {
        return id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public void setExecutionId(UUID executionId) {
        this.executionId = executionId;
    }

    public UUID getForkNodeId() {
        return forkNodeId;
    }

    public void setForkNodeId(UUID forkNodeId) {
        this.forkNodeId = forkNodeId;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public BranchExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(BranchExecutionStatus status) {
        this.status = status;
    }

    public Map<String, Object> getNexSnapshot() {
        return nexSnapshot;
    }

    public void setNexSnapshot(Map<String, Object> nexSnapshot) {
        this.nexSnapshot = nexSnapshot;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

