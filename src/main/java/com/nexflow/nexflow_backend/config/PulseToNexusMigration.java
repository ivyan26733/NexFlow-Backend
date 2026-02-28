package com.nexflow.nexflow_backend.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;

/**
 * One-time migration: convert all PULSE (HTTP Call) nodes to NEXUS.
 * Config (url, method, headers, body) is unchanged; NexusExecutor supports inline HTTP when connectorId is absent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PulseToNexusMigration {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void migratePulseToNexus() {
        try {
            int updated = jdbcTemplate.update(
                "UPDATE flow_nodes SET node_type = 'NEXUS' WHERE node_type = 'PULSE'"
            );
            if (updated > 0) {
                log.info("Migrated {} PULSE (HTTP Call) node(s) to NEXUS; config unchanged (inline HTTP).", updated);
            }
        } catch (Exception e) {
            log.warn("PULSE→NEXUS migration failed (non-fatal): {}", e.getMessage());
        }
    }
}
