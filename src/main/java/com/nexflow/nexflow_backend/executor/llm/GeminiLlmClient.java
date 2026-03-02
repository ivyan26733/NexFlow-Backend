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
public class GeminiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);
    private static final String DEFAULT_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent";

    /** Only Gemini models that support generateContent (excludes embed, predict, bidi-only, etc.). */
    private static final Set<String> GENERATE_CONTENT_MODELS = Set.of(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
            "gemini-2.0-flash-001",
            "gemini-2.0-flash-exp-image-generation",
            "gemini-2.0-flash-lite-001",
            "gemini-2.0-flash-lite",
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-pro-preview-tts",
            "gemma-3-1b-it",
            "gemma-3-4b-it",
            "gemma-3-12b-it",
            "gemma-3-27b-it",
            "gemma-3n-e4b-it",
            "gemma-3n-e2b-it",
            "gemini-flash-latest",
            "gemini-flash-lite-latest",
            "gemini-pro-latest",
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash-image",
            "gemini-2.5-flash-lite-preview-09-2025",
            "gemini-3-pro-preview",
            "gemini-3-flash-preview",
            "gemini-3.1-pro-preview",
            "gemini-3.1-pro-preview-customtools",
            "gemini-3-pro-image-preview",
            "nano-banana-pro-preview",
            "gemini-3.1-flash-image-preview",
            "gemini-robotics-er-1.5-preview",
            "gemini-2.5-computer-use-preview-10-2025",
            "deep-research-pro-preview-12-2025"
    );

    private static final String DEFAULT_MODEL = "gemini-2.0-flash";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public LlmProvider getProvider() { return LlmProvider.GEMINI; }

    @Override
    public String getDefaultModel() { return DEFAULT_MODEL; }

    @Override
    public String[] getKnownModels() {
        return GENERATE_CONTENT_MODELS.toArray(new String[0]);
    }

    @Override
    public LlmResponse call(LlmRequest req, String apiKey, String endpoint) {
        String model = req.getModel() != null && !req.getModel().isBlank() ? req.getModel() : getDefaultModel();
        if (!GENERATE_CONTENT_MODELS.contains(model)) {
            log.warn("[Gemini] Model not supported for generateContent: {}", model);
            return LlmResponse.error("Gemini model '" + model + "' does not support generateContent. Use a model from the AI provider list.");
        }
        String baseUrl = (endpoint != null && !endpoint.isBlank()) ? endpoint : DEFAULT_ENDPOINT;
        String url = baseUrl.replace("{model}", model) + "?key=" + apiKey;

        try {
            String fullUserText = req.getUserPrompt();
            if (req.getSystemPrompt() != null && !req.getSystemPrompt().isBlank()) {
                fullUserText = req.getSystemPrompt() + "\n\n" + fullUserText;
            }

            Map<String, Object> part = Map.of("text", fullUserText);
            Map<String, Object> content = Map.of("role", "user", "parts", List.of(part));
            List<Map<String, Object>> contents = new ArrayList<>();
            contents.add(content);

            Map<String, Object> genConfig = new LinkedHashMap<>();
            genConfig.put("temperature", req.getTemperature());
            genConfig.put("maxOutputTokens", req.getMaxTokens());
            genConfig.put("responseMimeType", "application/json");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", contents);
            body.put("generationConfig", genConfig);

            String jsonBody = mapper.writeValueAsString(body);

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (httpResp.statusCode() != 200) {
                log.error("[Gemini] HTTP {} — body truncated", httpResp.statusCode());
                return LlmResponse.error("Gemini API error " + httpResp.statusCode()
                        + ": " + extractError(httpResp.body()));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = mapper.readValue(httpResp.body(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) resp.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                return LlmResponse.error("Gemini returned no candidates");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> candidate = candidates.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> contentResp = (Map<String, Object>) candidate.get("content");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts = (List<Map<String, Object>>) contentResp.get("parts");
            String text = (String) parts.get(0).get("text");

            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) resp.get("usageMetadata");
            int inputTokens = usage != null ? ((Number) usage.getOrDefault("promptTokenCount", 0)).intValue() : 0;
            int outputTokens = usage != null ? ((Number) usage.getOrDefault("candidatesTokenCount", 0)).intValue() : 0;

            return LlmResponse.ok(text, model, inputTokens, outputTokens);

        } catch (Exception e) {
            log.error("[Gemini] Exception calling API", e);
            return LlmResponse.error("Gemini client exception: " + e.getMessage());
        }
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
