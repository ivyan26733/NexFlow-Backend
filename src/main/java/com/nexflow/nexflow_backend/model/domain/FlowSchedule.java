package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A cron rule that starts a flow automatically. */
@Entity
@Table(name = "flow_schedules", indexes = {
        @Index(name = "idx_flow_schedules_due", columnList = "enabled, next_run_at"),
        @Index(name = "idx_flow_schedules_flow", columnList = "flow_id")
})
@Data
public class FlowSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "flow_id", nullable = false)
    private UUID flowId;

    /** Who created the schedule. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String label;

    /** 6-field Spring cron: second minute hour day-of-month month day-of-week. */
    @Column(name = "cron_expression", nullable = false, length = 120)
    private String cronExpression;

    /** IANA zone the cron is evaluated in, e.g. "Asia/Kolkata". */
    @Column(nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(nullable = false)
    private boolean enabled = true;

    /** Input the flow receives on each run (its trigger body). */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /** Next time the schedule is due; null while disabled. */
    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_execution_id")
    private UUID lastExecutionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
