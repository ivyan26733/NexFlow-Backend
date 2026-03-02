-- Allow MLVOCA in provider check constraint (added for free testing provider).
-- Drop existing check and re-add with all current LlmProvider enum values.
ALTER TABLE llm_provider_configs DROP CONSTRAINT IF EXISTS llm_provider_configs_provider_check;
ALTER TABLE llm_provider_configs ADD CONSTRAINT llm_provider_configs_provider_check
    CHECK (provider IN ('ANTHROPIC', 'OPENAI', 'GEMINI', 'GROQ', 'MISTRAL', 'MLVOCA', 'CUSTOM'));
