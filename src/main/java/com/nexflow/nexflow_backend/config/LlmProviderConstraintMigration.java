package com.nexflow.nexflow_backend.config;

import com.nexflow.nexflow_backend.model.domain.LlmProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Updates the llm_provider_configs provider check constraint to include all current {@link LlmProvider} enum values.
 * Required when new providers (e.g. MLVOCA) are added and the DB constraint was created earlier.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmProviderConstraintMigration {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void updateProviderConstraint() {
        try {
            String allowed = String.join("', '",
                java.util.Arrays.stream(LlmProvider.values()).map(Enum::name).toList());
            jdbcTemplate.execute("ALTER TABLE llm_provider_configs DROP CONSTRAINT IF EXISTS llm_provider_configs_provider_check");
            jdbcTemplate.execute("ALTER TABLE llm_provider_configs ADD CONSTRAINT llm_provider_configs_provider_check CHECK (provider IN ('" + allowed + "'))");
            log.debug("Updated llm_provider_configs_provider_check to allow all LlmProvider values");
        } catch (Exception e) {
            log.warn("Could not update llm_provider_configs_provider_check: {}", e.getMessage());
        }
    }
}
