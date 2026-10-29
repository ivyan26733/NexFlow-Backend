package com.nexflow.nexflow_backend.config;

import com.nexflow.nexflow_backend.EdgeCondition;
import com.nexflow.nexflow_backend.model.domain.LlmProvider;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Small schema fixes Hibernate's ddl-auto=update cannot do on its own.
 *
 * Hibernate creates a CHECK constraint for every enum column when the table is first created, but
 * never widens it when a new enum value is added later. Without this, saving a node of a newer type
 * (or a newer edge condition / LLM provider) fails on databases created by an older version.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaMaintenance {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        renameLegacyPulseNodes(); // before the node_type constraint, which no longer allows PULSE
        syncEnumCheck("flow_nodes", "node_type", NodeType.values());
        syncEnumCheck("flow_edges", "condition_type", EdgeCondition.values());
        syncEnumCheck("llm_provider_configs", "provider", LlmProvider.values());
    }

    private void syncEnumCheck(String table, String column, Enum<?>[] values) {
        String constraint = table + "_" + column + "_check";
        String allowed = Arrays.stream(values).map(v -> "'" + v.name() + "'").collect(Collectors.joining(", "));
        try {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
            jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint
                    + " CHECK (" + column + " IN (" + allowed + "))");
        } catch (Exception e) {
            log.warn("Could not refresh constraint {}: {}", constraint, e.getMessage());
        }
    }

    /** The old "HTTP Call" node type PULSE was merged into NEXUS (inline HTTP); its config is unchanged. */
    private void renameLegacyPulseNodes() {
        try {
            int updated = jdbc.update("UPDATE flow_nodes SET node_type = 'NEXUS' WHERE node_type = 'PULSE'");
            if (updated > 0) log.info("Converted {} legacy PULSE node(s) to NEXUS.", updated);
        } catch (Exception e) {
            log.warn("Legacy PULSE → NEXUS conversion skipped: {}", e.getMessage());
        }
    }
}
