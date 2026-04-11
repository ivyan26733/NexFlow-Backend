package com.nexflow.nexflow_backend.service;

import com.nexflow.nexflow_backend.executor.llm.LlmClientFactory;
import com.nexflow.nexflow_backend.model.domain.LlmProviderConfig;
import com.nexflow.nexflow_backend.model.llm.LlmRequest;
import com.nexflow.nexflow_backend.model.llm.LlmResponse;
import com.nexflow.nexflow_backend.repository.LlmClient;
import com.nexflow.nexflow_backend.repository.LlmProviderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * NexFlow Assistant - chat service.
 * Uses the same LLM infrastructure as the AI node (LlmClientFactory + LlmProviderConfig).
 * Picks the first enabled provider from the DB to answer assistant questions.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final String PROMPT_PATH = "assistant/prompt.md";
    private static final String KNOWLEDGE_BASE = loadKnowledgeBase();

    private final LlmClientFactory clientFactory;
    private final LlmProviderConfigRepository providerConfigRepo;

    public AssistantService(LlmClientFactory clientFactory,
                            LlmProviderConfigRepository providerConfigRepo) {
        this.clientFactory = clientFactory;
        this.providerConfigRepo = providerConfigRepo;
    }

    /**
     * Send a user message (with optional conversation history and page context) to the LLM
     * and return the assistant's plain-text reply.
     */
    public String chat(String message, List<ChatMessage> history, Map<String, String> pageContext) {
        LlmProviderConfig config = providerConfigRepo.findAll().stream()
                .filter(LlmProviderConfig::isEnabled)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No LLM provider configured. Go to Settings -> AI Providers and add one."));

        LlmClient client = clientFactory.getClient(config.getProvider());

        StringBuilder userPrompt = new StringBuilder();

        // Include page context so the assistant knows where the user is
        if (pageContext != null && !pageContext.isEmpty()) {
            String pagePath = pageContext.getOrDefault("path", "");
            String pageName = pageContext.getOrDefault("name", "");
            if (!pagePath.isBlank()) {
                userPrompt.append("CURRENT PAGE CONTEXT:\n");
                userPrompt.append("The user is currently on the '").append(pageName).append("' page (").append(pagePath).append(").\n");
                userPrompt.append("Use this to give more relevant, context-specific help.\n\n");
            }
        }

        // Include recent conversation history (last 20 turns max)
        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 20);
            userPrompt.append("CONVERSATION HISTORY:\n");
            for (int i = start; i < history.size(); i++) {
                ChatMessage msg = history.get(i);
                String role = "user".equals(msg.role()) ? "User" : "Assistant";
                userPrompt.append(role).append(": ").append(msg.content()).append("\n");
            }
            userPrompt.append("\n---\n\n");
        }

        userPrompt.append("User: ").append(message.trim());

        // NOTE: outputSchema is intentionally NOT set so Gemini uses text/plain mode.
        // The system prompt already instructs the model to respond in plain text markdown.
        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(KNOWLEDGE_BASE);
        request.setUserPrompt(userPrompt.toString());
        request.setModel(client.getDefaultModel());
        request.setMaxTokens(2048);
        request.setTemperature(0.2);

        LlmResponse response = client.call(request, config.getApiKey(), config.getCustomEndpoint());

        if (!response.isSuccess()) {
            log.error("[Assistant] LLM call failed: {}", response.getErrorMessage());
            throw new RuntimeException("Assistant LLM call failed: " + response.getErrorMessage());
        }

        String reply = response.getRawText().trim();

        // Safety net: if the LLM still returned a JSON object (e.g. {"response":"..."}),
        // extract the text value from the most common keys before returning it.
        if (reply.startsWith("{") && reply.endsWith("}")) {
            reply = extractTextFromJson(reply);
        }

        return reply;
    }

    /**
     * Best-effort extraction of a plain-text reply from a JSON-wrapped LLM response.
     * Tries common field names: response, reply, text, message, content, answer.
     */
    private String extractTextFromJson(String json) {
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map =
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, java.util.Map.class);
            for (String key : new String[]{"response", "reply", "text", "message", "content", "answer", "output"}) {
                Object val = map.get(key);
                if (val instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            }
        } catch (Exception ignored) {}
        // If we can't extract, return as-is — better than losing the reply
        return json;
    }

    private static String loadKnowledgeBase() {
        ClassPathResource resource = new ClassPathResource(PROMPT_PATH);
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load assistant prompt from classpath:" + PROMPT_PATH, e);
        }
    }

    /** Simple record for chat messages sent from the frontend. */
    public record ChatMessage(String role, String content) {}
}
