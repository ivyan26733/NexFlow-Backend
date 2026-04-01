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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Per-node execution record for branch nodes so transaction detail can show input/output. */
@Entity
@Table(name = "node_executions", indexes = {
    @Index(name = "idx_node_exec_execution_id", columnList = "execution_id"),
    @Index(name = "idx_node_exec_execution_branch", columnList = "execution_id, branch_name")
})
public class NodeExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Column(name = "node_label", nullable = false, length = 255)
    private String nodeLabel;

    @Column(name = "node_type", nullable = false, length = 50)
    private String nodeType;

    @Column(name = "branch_name", length = 100)
    private String branchName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NodeExecutionStatus status = NodeExecutionStatus.RUNNING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_nex", columnDefinition = "text")
    private Map<String, Object> inputNex;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_nex", columnDefinition = "text")
    private Map<String, Object> outputNex;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getExecutionId() { return executionId; }
    public void setExecutionId(UUID executionId) { this.executionId = executionId; }
    public UUID getNodeId() { return nodeId; }
    public void setNodeId(UUID nodeId) { this.nodeId = nodeId; }
    public String getNodeLabel() { return nodeLabel; }
    public void setNodeLabel(String nodeLabel) { this.nodeLabel = nodeLabel; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public NodeExecutionStatus getStatus() { return status; }
    public void setStatus(NodeExecutionStatus status) { this.status = status; }
    public Map<String, Object> getInputNex() { return inputNex; }
    public void setInputNex(Map<String, Object> inputNex) { this.inputNex = inputNex; }
    public Map<String, Object> getOutputNex() { return outputNex; }
    public void setOutputNex(Map<String, Object> outputNex) { this.outputNex = outputNex; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
