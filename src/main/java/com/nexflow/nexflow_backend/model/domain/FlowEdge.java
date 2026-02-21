package com.nexflow.nexflow_backend.model.domain;

import com.nexflow.nexflow_backend.EdgeCondition;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "flow_edges")
@Data
public class FlowEdge {

    @Id
    private UUID id;

    @Column(name = "flow_id", nullable = false)
    private UUID flowId;

    @Column(name = "source_node_id", nullable = false)
    private UUID sourceNodeId;

    @Column(name = "target_node_id", nullable = false)
    private UUID targetNodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false)
    private EdgeCondition conditionType = EdgeCondition.DEFAULT;

    // Only populated when conditionType = CUSTOM, e.g. "{{variables.amount}} > 500"
    @Column(name = "condition_expr")
    private String conditionExpr;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
