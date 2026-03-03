package com.nexflow.nexflow_backend.model.domain;

import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import jakarta.persistence.*;
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

    @Enumerated(EnumType.STRING)
    private ExecutionStatus status = ExecutionStatus.RUNNING;

    @Column(name = "triggered_by")
    private String triggeredBy; // PULSE, MANUAL, SCHEDULE

    // Full NCO snapshot saved at the end of execution for audit/debug
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nco_snapshot")
    private Map<String, Object> ncoSnapshot;

    @Column(name = "started_at")
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;
}
