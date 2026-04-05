package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Records that a specific user or group has been granted access to a specific flow.
 * targetType = "USER" | "GROUP"
 */
@Entity
@Table(
    name = "flow_access",
    uniqueConstraints = @UniqueConstraint(columnNames = {"flow_id", "target_type", "target_id"})
)
@Data
public class FlowAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "flow_id", nullable = false)
    private UUID flowId;

    @Column(name = "target_type", nullable = false)
    private String targetType;  // "USER" or "GROUP"

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "granted_by")
    private UUID grantedBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
