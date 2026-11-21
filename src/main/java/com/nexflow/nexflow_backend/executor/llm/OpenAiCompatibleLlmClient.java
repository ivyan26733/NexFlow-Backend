package com.nexflow.nexflow_backend.executor.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.model.domain.LlmProvider;
import com.nexflow.nexflow_backend.model.llm.LlmRequest;
import com.nexflow.nexflow_backend.model.llm.LlmResponse;
import com.nexflow.nexflow_backend.repository.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final LlmProvider provider;
    private final String defaultEndpoint;
    private final String defaultModel;
    private final String[] knownModels;

    public OpenAiCompatibleLlmClient() {
        this.provider = LlmProvider.OPENAI;
        this.defaultEndpoint = "https://api.openai.com/v1/chat/completions";
        this.defaultModel = "gpt-4o-mini";
        this.knownModels = new String[]{"gpt-4o-mini", "gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo"};
    }

    public OpenAiCompatibleLlmClient(LlmProvider p, String ep, String model, String[] models) {
        this.provider = p;
        this.defaultEndpoint = ep;
        this.defaultModel = model;
        this.knownModels = models;
    }

    @Override
    public LlmProvider getProvider() { return provider; }

    @Override
    public String getDefaultModel() { return defaultModel; }

    @Override
    public String[] getKnownModels() { return knownModels; }

    @Override
    public LlmResponse call(LlmRequest req, String apiKey, String endpoint) {
        String url = (endpoint != null && !endpoint.isBlank()) ? endpoint : defaultEndpoint;
        String model = (req.getModel() != null && !req.getModel().isBlank()) ? req.getModel() : defaultModel;
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            if (req.getSystemPrompt() != null && !req.getSystemPrompt().isBlank()) {
                messages.add(Map.of("role", "system", "content", req.getSystemPrompt()));
            }
            messages.add(Map.of("role", "user", "content", req.getUserPrompt()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", req.getMaxTokens());
            body.put("temperature", req.getTemperature());
            // gpt-oss spends completion tokens on reasoning; on Groq those count against tight per-minute limits.
            if (provider == LlmProvider.GROQ && model.startsWith("openai/gpt-oss")) {
                body.put("reasoning_effort", "low");
            }
            // Use JSON mode only when the caller declared an outputSchema (AI nodes).
            // For assistant chat, outputSchema is null → omit response_format so the
            // model returns plain prose instead of a JSON object.
            if (req.getOutputSchema() != null && !req.getOutputSchema().isBlank()) {
                body.put("response_format", Map.of("type", "json_object"));
            }
            String jsonBody = mapper.writeValueAsString(body);
            String authHeader = "Bearer " + apiKey;
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authHeader)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (httpResp.statusCode() != 200) {
                log.error("[{}] HTTP {}", provider, httpResp.statusCode());
                return LlmResponse.error(provider.getDisplayName() + " API error " + httpResp.statusCode() + ": " + extractError(httpResp.body()));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = mapper.readValue(httpResp.body(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String text = (String) message.get("content");
            if ((text == null || text.isBlank()) && message.get("reasoning") instanceof String reasoning) {
                // gpt-oss on Groq sometimes leaves content empty and ends its reasoning with the JSON it meant to send.
                text = trailingJson(reasoning);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) resp.get("usage");
            int inputTokens = usage != null ? ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue() : 0;
            int outputTokens = usage != null ? ((Number) usage.getOrDefault("completion_tokens", 0)).intValue() : 0;
            String usedModel = (String) resp.getOrDefault("model", model);
            return LlmResponse.ok(text, usedModel, inputTokens, outputTokens);
        } catch (Exception e) {
            log.error("[{}] Exception calling API", provider, e);
            return LlmResponse.error(provider.getDisplayName() + " client exception: " + e.getMessage());
        }
    }

    /** The JSON object (or array) that {@code text} ends with, or null. */
    String trailingJson(String text) {
        String t = text.strip();
        if (!t.endsWith("}") && !t.endsWith("]")) return null;
        char open = t.endsWith("}") ? '{' : '[';
        for (int i = t.indexOf(open); i >= 0; i = t.indexOf(open, i + 1)) {
            String candidate = t.substring(i);
            try {
                mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(candidate);
                return candidate;
            } catch (Exception ignored) {
                // not a complete JSON value from here — try the next opening bracket
            }
        }
        return null;
    }

    private String extractError(String body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(body, Map.class);
            Object err = parsed.get("error");
            if (err instanceof Map) {
                Object msg = ((Map<?, ?>) err).get("message");
                return msg != null ? msg.toString() : fallbackBody(body);
            }
        } catch (Exception ignored) {}
        return fallbackBody(body);
    }

    private static String fallbackBody(String body) {
        return body != null && body.length() > 200 ? body.substring(0, 200) : (body != null ? body : "");
    }
}
