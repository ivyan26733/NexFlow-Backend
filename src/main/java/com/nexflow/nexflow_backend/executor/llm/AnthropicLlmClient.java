package com.nexflow.nexflow_backend.executor.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.model.domain.LlmProvider;
import com.nexflow.nexflow_backend.model.llm.LlmRequest;
import com.nexflow.nexflow_backend.model.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Component
public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);
    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public LlmProvider getProvider() { return LlmProvider.ANTHROPIC; }

    @Override
    public String getDefaultModel() { return "claude-haiku-4-5-20251001"; }

    @Override
    public String[] getKnownModels() {
        return new String[]{
            "claude-haiku-4-5-20251001",
            "claude-sonnet-4-6",
            "claude-opus-4-6"
        };
    }

    @Override
    public LlmResponse call(LlmRequest req, String apiKey, String endpoint) {
        String url = (endpoint != null && !endpoint.isBlank()) ? endpoint : DEFAULT_ENDPOINT;
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", req.getUserPrompt()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", req.getModel() != null ? req.getModel() : getDefaultModel());
            body.put("max_tokens", req.getMaxTokens());
            body.put("messages", messages);
            if (req.getSystemPrompt() != null && !req.getSystemPrompt().isBlank()) {
                body.put("system", req.getSystemPrompt());
            }
            String jsonBody = mapper.writeValueAsString(body);
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (httpResp.statusCode() != 200) {
                log.error("[Anthropic] HTTP {}", httpResp.statusCode());
                return LlmResponse.error("Anthropic API error " + httpResp.statusCode() + ": " + extractErrorMessage(httpResp.body()));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = mapper.readValue(httpResp.body(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
            String text = (String) content.get(0).get("text");
            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) resp.get("usage");
            int inputTokens = usage != null ? (int) usage.getOrDefault("input_tokens", 0) : 0;
            int outputTokens = usage != null ? (int) usage.getOrDefault("output_tokens", 0) : 0;
            String model = (String) resp.getOrDefault("model", getDefaultModel());
            return LlmResponse.ok(text, model, inputTokens, outputTokens);
        } catch (Exception e) {
            log.error("[Anthropic] Exception calling API", e);
            return LlmResponse.error("Anthropic client exception: " + e.getMessage());
        }
    }

    private String extractErrorMessage(String body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> err = mapper.readValue(body, Map.class);
            Object error = err.get("error");
            if (error instanceof Map) {
                Object msg = ((Map<?, ?>) error).get("message");
                return msg != null ? msg.toString() : fallbackBody(body);
            }
        } catch (Exception ignored) {}
        return fallbackBody(body);
    }

    private static String fallbackBody(String body) {
        return body != null && body.length() > 200 ? body.substring(0, 200) : (body != null ? body : "");
    }
}
