package com.nexflow.nexflow_backend.config;

import com.nexflow.nexflow_backend.EdgeCondition;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Updates the flow_edges condition_type check constraint to include all current {@link EdgeCondition} enum values.
 * Required when new condition types (e.g. CONTINUE for LOOP nodes) are added and the DB constraint was created earlier.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowEdgeConstraintMigration {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void updateConditionTypeConstraint() {
        try {
            String allowed = String.join("', '",
                Arrays.stream(EdgeCondition.values()).map(Enum::name).toList());
            jdbcTemplate.execute("ALTER TABLE flow_edges DROP CONSTRAINT IF EXISTS flow_edges_condition_type_check");
            jdbcTemplate.execute("ALTER TABLE flow_edges ADD CONSTRAINT flow_edges_condition_type_check CHECK (condition_type IN ('" + allowed + "'))");
            log.debug("Updated flow_edges_condition_type_check to allow all EdgeCondition values");
        } catch (Exception e) {
            log.warn("Could not update flow_edges_condition_type_check (constraint may already be correct): {}", e.getMessage());
        }
    }
}
