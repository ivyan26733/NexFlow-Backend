package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "flow_nodes")
@Data
public class FlowNode {

    @Id
    private UUID id;

    @Column(name = "flow_id", nullable = false)
    private UUID flowId;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false)
    private NodeType nodeType;

    private String label;

    // Stores all node-specific configuration and input mappings as JSON
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> config;

    // Canvas position
    @Column(name = "position_x")
    private Double positionX;

    @Column(name = "position_y")
    private Double positionY;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
