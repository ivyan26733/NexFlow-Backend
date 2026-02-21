package com.nexflow.nexflow_backend;

import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "execution_node_logs")
@Data
public class ExecutionNodeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Enumerated(EnumType.STRING)
    private NodeStatus status = NodeStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot")
    private Map<String, Object> inputSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_snapshot")
    private Map<String, Object> outputSnapshot;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
