package com.nexflow.nexflow_backend.model.domain;

import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "executions")
@Data
public class Execution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "flow_id", nullable = false)
    private UUID flowId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private ExecutionStatus status = ExecutionStatus.RUNNING;

    @Column(name = "triggered_by")
    private String triggeredBy; // PULSE, MANUAL, SCHEDULE

    /**
     * Original trigger payload for this execution.
     * Stored as JSON for audit/debug and for replaying executions.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private Map<String, Object> payload;

    // Full NCO snapshot saved at the end of execution for audit/debug
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nco_snapshot")
    private Map<String, Object> ncoSnapshot;

    @Column(name = "started_at")
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
