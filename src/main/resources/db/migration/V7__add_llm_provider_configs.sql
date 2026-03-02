-- LLM provider API keys (one row per provider). Used by AiNodeExecutor at runtime.
-- Compatible with Flyway; if using JPA ddl-auto=update, JPA may create the table instead.

CREATE TABLE IF NOT EXISTS llm_provider_configs (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    provider         VARCHAR(50)  NOT NULL UNIQUE,
    api_key          TEXT         NOT NULL,
    custom_endpoint  TEXT,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_llm_provider_configs_provider
    ON llm_provider_configs (provider);

CREATE INDEX IF NOT EXISTS idx_llm_provider_configs_enabled
    ON llm_provider_configs (enabled)
    WHERE enabled = TRUE;
