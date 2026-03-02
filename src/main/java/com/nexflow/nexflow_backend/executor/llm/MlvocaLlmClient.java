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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client for MLvoca free LLM API (Ollama-compatible). No API key required.
 * For temporary testing when Gemini/other keys are not available.
 */
@Component
public class MlvocaLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MlvocaLlmClient.class);
    private static final String DEFAULT_ENDPOINT = "https://mlvoca.com/api/generate";
    private static final String DEFAULT_MODEL = "tinyllama";
    private static final String[] KNOWN_MODELS = new String[]{"tinyllama", "deepseek-r1:1.5b"};

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public LlmProvider getProvider() {
        return LlmProvider.MLVOCA;
    }

    @Override
    public String getDefaultModel() {
        return DEFAULT_MODEL;
    }

    @Override
    public String[] getKnownModels() {
        return KNOWN_MODELS;
    }

    @Override
    public LlmResponse call(LlmRequest req, String apiKey, String endpoint) {
        String model = req.getModel() != null && !req.getModel().isBlank() ? req.getModel() : DEFAULT_MODEL;
        String url = (endpoint != null && !endpoint.isBlank()) ? endpoint : DEFAULT_ENDPOINT;

        String fullPrompt = req.getUserPrompt();
        if (req.getSystemPrompt() != null && !req.getSystemPrompt().isBlank()) {
            fullPrompt = req.getSystemPrompt() + "\n\n" + fullPrompt;
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("prompt", fullPrompt);
            body.put("stream", false);
            if (req.getMaxTokens() > 0) {
                body.put("options", Map.of("num_predict", req.getMaxTokens()));
            }
            String jsonBody = mapper.writeValueAsString(body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            // MLvoca does not require an API key
            HttpRequest httpReq = requestBuilder.build();

            HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (httpResp.statusCode() != 200) {
                log.error("[MLvoca] HTTP {} — {}", httpResp.statusCode(), extractError(httpResp.body()));
                return LlmResponse.error("MLvoca API error " + httpResp.statusCode() + ": " + extractError(httpResp.body()));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = mapper.readValue(httpResp.body(), Map.class);
            String text = (String) resp.get("response");
            if (text == null) {
                return LlmResponse.error("MLvoca returned no 'response' field");
            }
            // No token counts in MLvoca response; use 0
            return LlmResponse.ok(text.trim(), model, 0, 0);
        } catch (Exception e) {
            log.error("[MLvoca] Exception calling API", e);
            return LlmResponse.error("MLvoca client exception: " + e.getMessage());
        }
    }

    private String extractError(String body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(body, Map.class);
            Object err = parsed.get("error");
            if (err != null) return err.toString();
        } catch (Exception ignored) {
        }
        return body != null && body.length() > 200 ? body.substring(0, 200) : (body != null ? body : "");
    }
}
