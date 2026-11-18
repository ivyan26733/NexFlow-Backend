package com.nexflow.nexflow_backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.executor.llm.LlmClientFactory;
import com.nexflow.nexflow_backend.model.domain.LlmProvider;
import com.nexflow.nexflow_backend.model.domain.LlmProviderConfig;
import com.nexflow.nexflow_backend.model.domain.NexUser;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NexFlow Assistant.
 *
 * Answers with the knowledge base (assistant/prompt.md) plus live facts from the user's workspace.
 * Providers here are text-in / text-out, so tool use is done with a small text protocol: the model
 * replies with a single JSON line {"tool": "...", "args": {...}}, we run it (see {@link AssistantTools})
 * and call the model again with the result, up to {@link #MAX_TOOL_CALLS} times. Any other reply is the
 * final answer. This works the same with Claude, GPT, Gemini, Groq, Mistral or a custom endpoint.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final String PROMPT_PATH = "assistant/prompt.md";
    private static final String KNOWLEDGE_BASE = loadKnowledgeBase();
    static final int MAX_TOOL_CALLS = 5;
    private static final Pattern STUDIO_PATH = Pattern.compile("^/studio/([0-9a-fA-F-]{36})");
    private static final Pattern TRANSACTION_PATH = Pattern.compile("^/transactions/([0-9a-fA-F-]{36})");

    /** Strongest first — the assistant uses the best provider the user has enabled. */
    private static final List<LlmProvider> PREFERENCE = List.of(LlmProvider.ANTHROPIC, LlmProvider.OPENAI,
            LlmProvider.GEMINI, LlmProvider.MISTRAL, LlmProvider.GROQ, LlmProvider.CUSTOM, LlmProvider.MLVOCA);

    private static final String TOOL_GUIDE = """

            ================================================================
            LIVE WORKSPACE TOOLS
            ================================================================
            You can look up the user's real data. To use a tool, reply with ONLY one line of JSON and nothing else:
            {"tool": "<name>", "args": { ... }}
            You will then receive TOOL RESULT and can call another tool or answer. At most %d tool calls per question.

            Tools:
            %s
            Rules:
            - Use tools whenever the answer depends on the user's flows, runs, errors, schedules, channels, files,
              connectors or providers. Never invent ids, names, counts or errors — look them up.
            - WORKSPACE OVERVIEW (below) already lists the flows and 24h stats; don't call a tool for what it already shows.
            - To explain a failure: list_executions (status FAILURE) → get_execution → explain the failing step and the fix.
            - propose_run_flow only offers a button; never claim a flow ran.
            - Link to pages with markdown links using app paths, e.g. [Open in Studio](/studio/<id>), [See the run](/transactions/<id>),
              [Channels](/channels), [Files](/files).
            - The final answer is plain text / markdown, never a JSON object.
            """.formatted(MAX_TOOL_CALLS, AssistantTools.CATALOG);

    private final LlmClientFactory clientFactory;
    private final LlmProviderConfigRepository providerConfigRepo;
    private final AssistantTools tools;
    private final ObjectMapper mapper;

    public AssistantService(LlmClientFactory clientFactory,
                            LlmProviderConfigRepository providerConfigRepo,
                            AssistantTools tools,
                            ObjectMapper mapper) {
        this.clientFactory = clientFactory;
        this.providerConfigRepo = providerConfigRepo;
        this.tools = tools;
        this.mapper = mapper;
    }

    /** Simple record for chat messages sent from the frontend. */
    public record ChatMessage(String role, String content) {}

    /** What the Studio / page sends along with a question. All optional. */
    public record ChatContext(Map<String, String> page, Object flowSnapshot, Object transactions) {}

    public record ToolUse(String tool, Map<String, Object> args) {}

    public record AssistantReply(String reply, List<AssistantTools.Action> actions, List<ToolUse> toolsUsed, String provider) {}

    public AssistantReply chat(String message, List<ChatMessage> history, ChatContext context, NexUser user) {
        LlmProviderConfig config = providerConfigRepo.findByUserIdAndEnabledTrue(user.getId()).stream()
                .min(Comparator.comparingInt(c -> PREFERENCE.indexOf(c.getProvider())))
                .orElseThrow(() -> new IllegalStateException(
                        "No LLM provider configured. Go to Settings → AI Providers and add one."));
        LlmClient client = clientFactory.getClient(config.getProvider());

        String preamble = buildPreamble(history, context, user);
        List<AssistantTools.Action> actions = new ArrayList<>();
        List<ToolUse> used = new ArrayList<>();
        StringBuilder scratch = new StringBuilder();

        for (int step = 0; ; step++) {
            boolean lastChance = step >= MAX_TOOL_CALLS;
            StringBuilder prompt = new StringBuilder(preamble).append("User: ").append(message.trim()).append("\n");
            if (!scratch.isEmpty()) prompt.append("\n").append(scratch);
            prompt.append(lastChance
                    ? "\nYou have used all your tool calls. Write the final answer now from what you know (no JSON)."
                    : "\nReply with a tool call (one JSON line) or your final answer.");

            String reply = ask(client, config, prompt.toString(), 2048);
            ToolUse call = lastChance ? null : parseToolCall(reply);
            if (call == null) {
                return new AssistantReply(finalText(reply), actions, used, config.getProvider().name());
            }
            String result = tools.call(call.tool(), call.args(), user, actions);
            used.add(call);
            log.debug("[Assistant] tool {} args={} → {} chars", call.tool(), call.args(), result.length());
            scratch.append("TOOL CALL: ").append(toJson(Map.of("tool", call.tool(), "args", call.args()))).append("\n")
                    .append("TOOL RESULT: ").append(result).append("\n\n");
        }
    }

    private String ask(LlmClient client, LlmProviderConfig config, String userPrompt, int maxTokens) {
        // outputSchema is intentionally NOT set so Gemini uses text/plain mode.
        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(KNOWLEDGE_BASE + TOOL_GUIDE);
        request.setUserPrompt(userPrompt);
        request.setModel(client.getDefaultModel());
        request.setMaxTokens(maxTokens);
        request.setTemperature(0.2);
        LlmResponse response = client.call(request, config.getApiKey(), config.getCustomEndpoint());
        if (!response.isSuccess()) {
            log.error("[Assistant] LLM call failed: {}", response.getErrorMessage());
            throw new RuntimeException("Assistant LLM call failed: " + response.getErrorMessage());
        }
        return response.getRawText() != null ? response.getRawText().trim() : "";
    }

    /** Page, workspace overview, Studio canvas, recent runs and conversation history. */
    private String buildPreamble(List<ChatMessage> history, ChatContext ctx, NexUser user) {
        StringBuilder p = new StringBuilder();
        Map<String, String> page = ctx != null && ctx.page() != null ? ctx.page() : Map.of();
        String path = page.getOrDefault("path", "");
        if (!path.isBlank()) {
            p.append("CURRENT PAGE CONTEXT:\nThe user is on the '").append(page.getOrDefault("name", ""))
                    .append("' page (").append(path).append(").\n\n");
        }
        p.append("USER: ").append(user.getName() != null ? user.getName() : user.getEmail())
                .append(" (role ").append(user.getRole()).append(")\n\n");
        try {
            p.append("WORKSPACE OVERVIEW (live):\n").append(tools.overview(user)).append("\n\n");
        } catch (Exception e) {
            log.warn("[Assistant] overview failed: {}", e.getMessage());
        }

        // What the user is looking at right now.
        Matcher studio = STUDIO_PATH.matcher(path);
        Matcher txn = TRANSACTION_PATH.matcher(path);
        if (ctx != null && ctx.flowSnapshot() != null) {
            p.append("FLOW OPEN IN STUDIO (unsaved canvas as the user sees it):\n")
                    .append(tools.toJson(AssistantTools.redact(mapper.convertValue(ctx.flowSnapshot(), Object.class)))).append("\n\n");
        } else if (studio.find()) {
            p.append("FLOW OPEN IN STUDIO:\n").append(tools.call("get_flow", Map.of("flow", studio.group(1)), user, new ArrayList<>())).append("\n\n");
        }
        if (txn.find()) {
            p.append("RUN THE USER IS VIEWING:\n").append(tools.call("get_execution", Map.of("executionId", txn.group(1)), user, new ArrayList<>())).append("\n\n");
        }
        if (ctx != null && ctx.transactions() != null) {
            p.append("RECENT RUNS OF THIS FLOW (sent by Studio):\n")
                    .append(tools.toJson(AssistantTools.redact(mapper.convertValue(ctx.transactions(), Object.class)))).append("\n\n");
        }

        if (history != null && !history.isEmpty()) {
            p.append("CONVERSATION HISTORY:\n");
            for (ChatMessage msg : history.subList(Math.max(0, history.size() - 20), history.size())) {
                p.append("user".equals(msg.role()) ? "User: " : "Assistant: ").append(msg.content()).append("\n");
            }
            p.append("\n---\n\n");
        }
        return p.toString();
    }

    // ── Reply parsing ────────────────────────────────────────────────────────

    private static final Pattern FENCE = Pattern.compile("^```(?:json)?\\s*(.*?)\\s*```$", Pattern.DOTALL);

    /** A reply that is nothing but {"tool": ..., "args": {...}} (optionally fenced) is a tool call. */
    ToolUse parseToolCall(String reply) {
        if (reply == null) return null;
        String t = reply.trim();
        Matcher fence = FENCE.matcher(t);
        if (fence.matches()) t = fence.group(1).trim();
        if (!t.startsWith("{") || !t.endsWith("}") || !t.contains("\"tool\"")) return null;
        try {
            Map<String, Object> obj = mapper.readValue(t, new TypeReference<>() {});
            if (!(obj.get("tool") instanceof String tool) || tool.isBlank()) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> args = obj.get("args") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
            return new ToolUse(tool.trim(), args);
        } catch (Exception e) {
            return null;
        }
    }

    /** Safety net: if the model wrapped its answer in JSON ({"response": "..."}), unwrap it. */
    private String finalText(String reply) {
        String t = reply.trim();
        if (t.startsWith("{") && t.endsWith("}")) {
            try {
                Map<String, Object> map = mapper.readValue(t, new TypeReference<>() {});
                for (String key : new String[] { "response", "reply", "text", "message", "content", "answer", "output" }) {
                    if (map.get(key) instanceof String s && !s.isBlank()) return s.trim();
                }
            } catch (Exception ignored) {
                // not JSON — return as-is
            }
        }
        return t.isEmpty() ? "I couldn't come up with an answer. Try rephrasing the question." : t;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static String loadKnowledgeBase() {
        ClassPathResource resource = new ClassPathResource(PROMPT_PATH);
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load assistant prompt from classpath:" + PROMPT_PATH, e);
        }
    }
}
