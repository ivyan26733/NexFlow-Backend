package com.nexflow.nexflow_backend.executor.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.executor.ReferenceResolver;
import com.nexflow.nexflow_backend.executor.llm.LlmClient;
import com.nexflow.nexflow_backend.executor.llm.LlmClientFactory;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.LlmProvider;
import com.nexflow.nexflow_backend.model.domain.LlmProviderConfig;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.llm.LlmRequest;
import com.nexflow.nexflow_backend.model.llm.LlmResponse;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.repository.LlmProviderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AiNodeExecutor implements com.nexflow.nexflow_backend.executor.NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiNodeExecutor.class);
    private static final int MAX_INPUT_CHARS = 12_000;
    /** Matches all {{...}} references in prompt text. */
    private static final Pattern REF_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");
    private static final List<String> FORBIDDEN_PATH_PREFIXES = List.of(
        "nex.dbPassword", "nex.password", "nex.apiKey",
        "nex.authToken", "nex.secret", "nex.credentials"
    );

    private final LlmClientFactory clientFactory;
    private final LlmProviderConfigRepository providerConfigRepo;
    private final ReferenceResolver resolver;
    private final ObjectMapper mapper;

    public AiNodeExecutor(LlmClientFactory clientFactory,
                          LlmProviderConfigRepository providerConfigRepo,
                          ReferenceResolver resolver,
                          ObjectMapper mapper) {
        this.clientFactory = clientFactory;
        this.providerConfigRepo = providerConfigRepo;
        this.resolver = resolver;
        this.mapper = mapper;
    }

    @Override
    public NodeType supportedType() {
        return NodeType.AI;
    }

    @Override
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> cfg = node.getConfig();
        if (cfg == null) return failure(node.getId().toString(), Map.of(), "AI node has no config.");

        String providerStr = (String) cfg.getOrDefault("provider", "ANTHROPIC");
        String model = (String) cfg.get("model");
        String prompt = (String) cfg.getOrDefault("prompt", "");
        String outputSchema = (String) cfg.get("outputSchema");
        int maxTokens = cfg.containsKey("maxTokens") ? ((Number) cfg.get("maxTokens")).intValue() : 1000;
        double temperature = cfg.containsKey("temperature") ? ((Number) cfg.get("temperature")).doubleValue() : 0.0;

        if (prompt == null || prompt.isBlank()) {
            return failure(node.getId().toString(), Map.of(), "AI node has no prompt configured.");
        }

        LlmProvider provider;
        try {
            provider = LlmProvider.valueOf(providerStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return failure(node.getId().toString(), Map.of(), "Unknown LLM provider: '" + providerStr + "'.");
        }

        Optional<LlmProviderConfig> providerCfgOpt = providerConfigRepo.findByProvider(provider);
        if (providerCfgOpt.isEmpty() || !providerCfgOpt.get().isEnabled()) {
            return failure(node.getId().toString(), Map.of(),
                "No API key configured for provider '" + provider.getDisplayName() + "'. Go to Settings → AI Providers and add your key.");
        }
        LlmProviderConfig providerCfg = providerCfgOpt.get();

        @SuppressWarnings("unchecked")
        List<Map<String, String>> rawBindings = (List<Map<String, String>>) cfg.getOrDefault("inputBindings", List.of());
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (Map<String, String> binding : rawBindings) {
            String name = binding.get("name");
            String nexPath = binding.get("nexPath");
            if (name == null || nexPath == null || name.isBlank() || nexPath.isBlank()) continue;
            for (String forbidden : FORBIDDEN_PATH_PREFIXES) {
                if (nexPath.toLowerCase().contains(forbidden.toLowerCase())) {
                    return failure(node.getId().toString(), Map.of(),
                        "AI node input binding '" + name + "' references a forbidden path. Credentials cannot be passed to AI nodes.");
                }
            }
            String normPath = normaliseNexPath(nexPath);
            Object resolved = resolver.resolveToObject("{{" + normPath + "}}", nco);
            inputs.put(name, resolved != null ? resolved : "");
        }

        String inputsJson;
        try {
            inputsJson = mapper.writeValueAsString(inputs);
        } catch (JsonProcessingException e) {
            return failure(node.getId().toString(), Map.of(), "Failed to serialise AI node inputs: " + e.getMessage());
        }
        if (inputsJson.length() > MAX_INPUT_CHARS) {
            return failure(node.getId().toString(), Map.of(),
                "AI node inputs exceed maximum size (" + MAX_INPUT_CHARS + " chars). Reduce the data passed via input bindings.");
        }

        // Resolve ALL {{...}} refs in prompt: binding names first, then direct paths (nex.*, nodes.*, etc.)
        String resolvedPrompt = resolveAllRefsInPrompt(prompt, inputs, nco);
        if (resolvedPrompt.length() > MAX_INPUT_CHARS) {
            return failure(node.getId().toString(), Map.of(),
                "Resolved prompt is too large (" + resolvedPrompt.length() + " chars). Reduce referenced data.");
        }
        String systemPrompt = buildSystemPrompt(outputSchema);
        String userPrompt = buildUserPrompt(resolvedPrompt, inputsJson, inputs.isEmpty());

        LlmRequest llmReq = new LlmRequest();
        llmReq.setSystemPrompt(systemPrompt);
        llmReq.setUserPrompt(userPrompt);
        llmReq.setModel(model);
        llmReq.setMaxTokens(maxTokens);
        llmReq.setTemperature(temperature);
        llmReq.setOutputSchema(outputSchema);
        llmReq.setInputs(inputs);

        LlmClient client = clientFactory.getClient(provider);
        LlmResponse llmResp = client.call(llmReq, providerCfg.getApiKey(), providerCfg.getCustomEndpoint());

        if (!llmResp.isSuccess()) {
            return failure(node.getId().toString(), Map.of("provider", provider.name()), "LLM call failed: " + llmResp.getErrorMessage());
        }

        Object parsedOutput = extractJson(llmResp.getRawText());
        if (parsedOutput == null) {
            log.warn("[AiNode] First parse failed, retrying with JSON nudge");
            llmReq.setUserPrompt(userPrompt + "\n\nIMPORTANT: Your response must be valid JSON only. No explanation text.");
            llmResp = client.call(llmReq, providerCfg.getApiKey(), providerCfg.getCustomEndpoint());
            if (!llmResp.isSuccess()) {
                return failure(node.getId().toString(), Map.of(), "LLM retry failed: " + llmResp.getErrorMessage());
            }
            parsedOutput = extractJson(llmResp.getRawText());
        }
        if (parsedOutput == null) {
            return failure(node.getId().toString(), Map.of(),
                "AI node could not parse a valid JSON object from the model response. Raw: " + truncate(llmResp.getRawText(), 300));
        }

        Map<String, Object> successOutput = new LinkedHashMap<>();
        successOutput.put("result", parsedOutput);
        successOutput.put("model", llmResp.getModel());
        successOutput.put("inputTokens", llmResp.getInputTokens());
        successOutput.put("outputTokens", llmResp.getOutputTokens());
        successOutput.put("provider", provider.name());
        successOutput.put("rawResponse", llmResp.getRawText());
        successOutput.put("resolvedPrompt", resolvedPrompt);

        log.info("[AiNode] '{}' completed. Tokens: {}in/{}out. Provider: {}",
            node.getLabel(), llmResp.getInputTokens(), llmResp.getOutputTokens(), provider);

        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(NodeType.AI.name())
                .status(NodeStatus.SUCCESS)
                .input(Map.of("provider", provider.name(), "prompt", prompt))
                .successOutput(successOutput)
                .build();
    }

    private String buildSystemPrompt(String outputSchema) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a data processing engine embedded inside an automation workflow.\n");
        sb.append("You will receive a task description and a JSON object called INPUTS.\n");
        sb.append("You must ONLY work with the data provided in INPUTS.\n");
        sb.append("You must return ONLY a valid JSON object — no explanation, no markdown, no code fences.\n");
        sb.append("Do not attempt to access external systems, databases, credentials, or files.\n");
        sb.append("Do not include any text before or after the JSON object.\n");
        if (outputSchema != null && !outputSchema.isBlank()) {
            sb.append("\nYour output JSON must match this schema:\n").append(outputSchema).append("\n");
        }
        return sb.toString();
    }

    private String buildUserPrompt(String taskPrompt, String inputsJson, boolean noBindings) {
        if (noBindings || "{}".equals(inputsJson)) {
            return "TASK:\n" + taskPrompt + "\n\nRespond with a valid JSON object only.";
        }
        return "TASK:\n" + taskPrompt + "\n\nINPUTS:\n" + inputsJson + "\n\nRespond with a valid JSON object only.";
    }

    /**
     * Strip leading "input." prefix so input.nex.start.body.a → nex.start.body.a (and similar).
     */
    private String normaliseNexPath(String path) {
        if (path == null) return "";
        if (path.startsWith("input.")) path = path.substring(6);
        return path.trim();
    }

    /**
     * Convert a resolved value to a string for inline prompt substitution.
     * Primitives → toString(); objects/arrays → compact JSON.
     */
    private String toReadableString(Object val) {
        if (val == null) return "null";
        if (val instanceof String || val instanceof Number || val instanceof Boolean) return val.toString();
        try {
            return mapper.writeValueAsString(val);
        } catch (Exception e) {
            return val.toString();
        }
    }

    /**
     * Resolve every {{...}} token in the prompt.
     * - If the token matches a named input binding → use that value.
     * - Otherwise treat as a direct path (nex.*, nodes.*, etc.): normalise "input." prefix and resolve via ReferenceResolver.
     * - If resolution fails, use "[unresolved: token]" and log a warning.
     * - Fallback: for nex.start.* when null, try nodes.start.output.* (trigger is always there).
     */
    private String resolveAllRefsInPrompt(String prompt, Map<String, Object> inputs, NexflowContextObject nco) {
        if (prompt == null || !prompt.contains("{{")) return prompt;
        Matcher m = REF_PATTERN.matcher(prompt);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String token = m.group(1).trim();
            String replacement;
            if (inputs.containsKey(token)) {
                replacement = toReadableString(inputs.get(token));
            } else {
                String normPath = normaliseNexPath(token);
                Object resolved = resolver.resolveToObject("{{" + normPath + "}}", nco);
                if (resolved == null && (token.startsWith("input.nex.start.") || token.startsWith("nex.start."))) {
                    String fallback = normPath.replaceFirst("^nex\\.start\\.", "nodes.start.output.");
                    resolved = resolver.resolveToObject("{{" + fallback + "}}", nco);
                }
                if (resolved != null) {
                    replacement = toReadableString(resolved);
                } else {
                    log.warn("[AiNode] Could not resolve reference '{{{}}}' — leaving placeholder", token);
                    replacement = "[unresolved: " + token + "]";
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Object extractJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "").trim();
        }
        int start = -1;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '{' || c == '[') { start = i; break; }
        }
        if (start == -1) return null;
        String candidate = cleaned.substring(start);
        try {
            return mapper.readValue(candidate, Object.class);
        } catch (Exception e) {
            log.debug("[AiNode] JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private NodeContext failure(String nodeId, Map<String, Object> input, String message) {
        log.error("[AiNode] FAILURE: {}", message);
        Map<String, Object> failureOutput = new LinkedHashMap<>();
        failureOutput.put("error", message);
        return NodeContext.builder()
                .nodeId(nodeId)
                .nodeType(NodeType.AI.name())
                .status(NodeStatus.FAILURE)
                .input(input != null ? input : Map.of())
                .failureOutput(failureOutput)
                .errorMessage(message)
                .build();
    }

    private String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
