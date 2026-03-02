package com.nexflow.nexflow_backend.model.domain;

/**
 * Supported LLM providers for the AI node.
 * Each provider maps to a concrete LlmClient implementation.
 */
public enum LlmProvider {

    ANTHROPIC("Anthropic Claude",    "https://api.anthropic.com/v1/messages"),
    OPENAI("OpenAI GPT",             "https://api.openai.com/v1/chat/completions"),
    GEMINI("Google Gemini",          "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"),
    GROQ("Groq (Fast inference)",    "https://api.groq.com/openai/v1/chat/completions"),
    MISTRAL("Mistral AI",            "https://api.mistral.ai/v1/chat/completions"),
    MLVOCA("MLvoca (testing)",      "https://mlvoca.com/api/generate"),  // free, no API key required
    CUSTOM("Custom / Self-hosted",   "");  // user supplies their own endpoint

    private final String displayName;
    private final String defaultEndpoint;

    LlmProvider(String displayName, String defaultEndpoint) {
        this.displayName    = displayName;
        this.defaultEndpoint = defaultEndpoint;
    }

    public String getDisplayName()    { return displayName; }
    public String getDefaultEndpoint() { return defaultEndpoint; }
}
