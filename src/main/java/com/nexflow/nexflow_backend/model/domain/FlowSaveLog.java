package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "flow_save_log", indexes = {
        @Index(name = "idx_fsl_flow_saved", columnList = "flow_id, saved_at"),
        @Index(name = "idx_fsl_user_saved", columnList = "user_id, saved_at")
})
@Data
public class FlowSaveLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "flow_id", nullable = false)
    private UUID flowId;

    @Column(name = "user_id")
    private UUID userId;

    /** CANVAS_SAVED or FLOW_CREATED */
    @Column(name = "activity_type", nullable = false, length = 32)
    private String activityType;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt = Instant.now();
}
