package com.nexflow.nexflow_backend.config;

import com.nexflow.nexflow_backend.model.domain.NodeType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Updates the flow_nodes node_type check constraint to include all current {@link NodeType} enum values.
 * Required when new node types (e.g. SCRIPT) are added and the DB constraint was created earlier.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowNodeConstraintMigration {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void updateNodeTypeConstraint() {
        try {
            String allowed = String.join("', '",
                java.util.Arrays.stream(NodeType.values()).map(Enum::name).toList());
            jdbcTemplate.execute("ALTER TABLE flow_nodes DROP CONSTRAINT IF EXISTS flow_nodes_node_type_check");
            jdbcTemplate.execute("ALTER TABLE flow_nodes ADD CONSTRAINT flow_nodes_node_type_check CHECK (node_type IN ('" + allowed + "'))");
            log.debug("Updated flow_nodes_node_type_check to allow all NodeType values");
        } catch (Exception e) {
            log.warn("Could not update flow_nodes_node_type_check (constraint may already be correct): {}", e.getMessage());
        }
    }
}
