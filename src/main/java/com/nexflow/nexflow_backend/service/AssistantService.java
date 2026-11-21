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
 *
 * Providers with small per-minute token limits (Groq's free tier allows 8,000) get a {@link Budget#SMALL}
 * request: only the knowledge sections relevant to the question, trimmed context and one tool call.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final String PROMPT_PATH = "assistant/prompt.md";
    private static final AssistantKnowledge KNOWLEDGE = new AssistantKnowledge(loadKnowledgeBase());
    static final int MAX_TOOL_CALLS = 5;
    /** When the provider says "try again in Ns", wait and retry — up to this many times and this long in total. */
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final double MAX_RATE_LIMIT_WAIT_SECONDS = 25;
    private static final Pattern RETRY_AFTER = Pattern.compile("(?i)try again in ([0-9.]+)\\s*(ms|s)");

    /** How much one request may carry. Limits are in characters (about 4 per token). */
    record Budget(int knowledgeChars, int contextChars, int toolResultChars, int maxToolCalls, int maxTokens,
                  int historyMessages, int historyChars) {
        static final Budget FULL = new Budget(Integer.MAX_VALUE, 60_000, 12_000, MAX_TOOL_CALLS, 2048, 20, 4_000);
        /** About 3k tokens per request, so a question plus one tool call fits in 8k tokens per minute. */
        static final Budget SMALL = new Budget(4_000, 2_200, 2_000, 1, 900, 4, 300);

        static Budget forProvider(LlmProvider provider) {
            return provider == LlmProvider.GROQ || provider == LlmProvider.MLVOCA ? SMALL : FULL;
        }
    }
    private static final Pattern STUDIO_PATH = Pattern.compile("^/studio/([0-9a-fA-F-]{36})");
    private static final Pattern TRANSACTION_PATH = Pattern.compile("^/transactions/([0-9a-fA-F-]{36})");

    /** Strongest first — the assistant uses the best provider the user has enabled. */
    private static final List<LlmProvider> PREFERENCE = List.of(LlmProvider.ANTHROPIC, LlmProvider.OPENAI,
            LlmProvider.GEMINI, LlmProvider.MISTRAL, LlmProvider.GROQ, LlmProvider.CUSTOM, LlmProvider.MLVOCA);

    private static String toolGuide(int maxToolCalls) {
        return """

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
            """.formatted(maxToolCalls, AssistantTools.CATALOG);
    }

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
        Budget budget = Budget.forProvider(config.getProvider());

        Map<String, String> page = context != null && context.page() != null ? context.page() : Map.of();
        String knowledge = budget == Budget.FULL
                ? KNOWLEDGE.full()
                : KNOWLEDGE.compact(message + " " + page.getOrDefault("name", ""), budget.knowledgeChars());
        String systemPrompt = knowledge + toolGuide(budget.maxToolCalls());
        String preamble = buildPreamble(history, context, user, budget);
        List<AssistantTools.Action> actions = new ArrayList<>();
        List<ToolUse> used = new ArrayList<>();
        StringBuilder scratch = new StringBuilder();

        for (int step = 0; ; step++) {
            boolean lastChance = step >= budget.maxToolCalls();
            StringBuilder prompt = new StringBuilder(preamble).append("User: ").append(message.trim()).append("\n");
            if (!scratch.isEmpty()) prompt.append("\n").append(scratch);
            prompt.append(lastChance
                    ? "\nYou have used all your tool calls. Write the final answer now from what you know (no JSON)."
                    : "\nReply with a tool call (one JSON line) or your final answer.");

            String reply = ask(client, config, systemPrompt, prompt.toString(), budget.maxTokens());
            ToolUse call = lastChance ? null : parseToolCall(reply);
            if (call == null) {
                return new AssistantReply(finalText(reply), actions, used, config.getProvider().name());
            }
            String result = clip(tools.call(call.tool(), call.args(), user, actions), budget.toolResultChars());
            used.add(call);
            log.debug("[Assistant] tool {} args={} → {} chars", call.tool(), call.args(), result.length());
            scratch.append("TOOL CALL: ").append(toJson(Map.of("tool", call.tool(), "args", call.args()))).append("\n")
                    .append("TOOL RESULT: ").append(result).append("\n\n");
        }
    }

    private String ask(LlmClient client, LlmProviderConfig config, String systemPrompt, String userPrompt, int maxTokens) {
        // outputSchema is intentionally NOT set so Gemini uses text/plain mode.
        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(systemPrompt);
        request.setUserPrompt(userPrompt);
        request.setModel(client.getDefaultModel());
        request.setMaxTokens(maxTokens);
        request.setTemperature(0.2);
        LlmResponse response = client.call(request, config.getApiKey(), config.getCustomEndpoint());
        double waited = 0;
        for (int retry = 0; retry < MAX_RATE_LIMIT_RETRIES && !response.isSuccess(); retry++) {
            Double wait = retryAfterSeconds(response.getErrorMessage());
            if (wait == null || waited + wait > MAX_RATE_LIMIT_WAIT_SECONDS) break;
            log.info("[Assistant] rate limited, retrying in {}s", wait);
            try {
                Thread.sleep((long) (wait * 1000) + 250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            waited += wait;
            response = client.call(request, config.getApiKey(), config.getCustomEndpoint());
        }
        if (!response.isSuccess()) {
            log.error("[Assistant] LLM call failed: {}", response.getErrorMessage());
            throw new RuntimeException(friendlyError(config.getProvider(), response.getErrorMessage()));
        }
        return response.getRawText() != null ? response.getRawText().trim() : "";
    }

    /** Seconds the provider asked us to wait ("Please try again in 7.5s"), or null if it isn't a rate limit. */
    static Double retryAfterSeconds(String error) {
        if (error == null || !(error.contains("429") || error.toLowerCase().contains("rate limit"))) return null;
        Matcher m = RETRY_AFTER.matcher(error);
        if (!m.find()) return null;
        double value = Double.parseDouble(m.group(1));
        return "ms".equalsIgnoreCase(m.group(2)) ? value / 1000 : value;
    }

    static String friendlyError(LlmProvider provider, String error) {
        String e = error == null ? "" : error;
        boolean tooBig = e.contains("413") || e.toLowerCase().contains("request too large");
        boolean limited = e.contains("429") || e.toLowerCase().contains("rate limit");
        if (tooBig || limited) {
            return provider.getDisplayName() + " is over its per-minute usage limit"
                    + (tooBig ? " for a question this size" : "")
                    + ". Wait a minute and ask again, ask a shorter question, or add a provider with higher limits "
                    + "in Settings → AI Providers. (" + e + ")";
        }
        return "Assistant LLM call failed: " + e;
    }

    /** Page, workspace overview, Studio canvas, recent runs and conversation history. */
    private String buildPreamble(List<ChatMessage> history, ChatContext ctx, NexUser user, Budget budget) {
        StringBuilder p = new StringBuilder();
        Map<String, String> page = ctx != null && ctx.page() != null ? ctx.page() : Map.of();
        String path = page.getOrDefault("path", "");
        if (!path.isBlank()) {
            p.append("CURRENT PAGE CONTEXT:\nThe user is on the '").append(page.getOrDefault("name", ""))
                    .append("' page (").append(path).append(").\n\n");
        }
        p.append("USER: ").append(user.getName() != null ? user.getName() : user.getEmail())
                .append(" (role ").append(user.getRole()).append(")\n\n");
        // What the user is looking at right now comes first; the overview gets whatever budget is left.
        int[] room = { budget.contextChars() };
        Matcher studio = STUDIO_PATH.matcher(path);
        Matcher txn = TRANSACTION_PATH.matcher(path);
        if (ctx != null && ctx.flowSnapshot() != null) {
            appendBlock(p, "FLOW OPEN IN STUDIO (unsaved canvas as the user sees it)",
                    tools.toJson(AssistantTools.redact(mapper.convertValue(ctx.flowSnapshot(), Object.class))), room);
        } else if (studio.find()) {
            appendBlock(p, "FLOW OPEN IN STUDIO", tools.call("get_flow", Map.of("flow", studio.group(1)), user, new ArrayList<>()), room);
        }
        if (txn.find()) {
            appendBlock(p, "RUN THE USER IS VIEWING", tools.call("get_execution", Map.of("executionId", txn.group(1)), user, new ArrayList<>()), room);
        }
        if (ctx != null && ctx.transactions() != null) {
            appendBlock(p, "RECENT RUNS OF THIS FLOW (sent by Studio)",
                    tools.toJson(AssistantTools.redact(mapper.convertValue(ctx.transactions(), Object.class))), room);
        }
        try {
            appendBlock(p, "WORKSPACE OVERVIEW (live)", tools.overview(user), room);
        } catch (Exception e) {
            log.warn("[Assistant] overview failed: {}", e.getMessage());
        }

        if (history != null && !history.isEmpty()) {
            p.append("CONVERSATION HISTORY:\n");
            int from = Math.max(0, history.size() - budget.historyMessages());
            for (ChatMessage msg : history.subList(from, history.size())) {
                p.append("user".equals(msg.role()) ? "User: " : "Assistant: ")
                        .append(clip(msg.content(), budget.historyChars())).append("\n");
            }
            p.append("\n---\n\n");
        }
        return p.toString();
    }

    /** Appends a context block clipped to the room left, and uses that room up. Skips it when nothing is left. */
    private static void appendBlock(StringBuilder p, String title, String text, int[] room) {
        if (text == null || text.isBlank() || room[0] < 200) return;
        String clipped = clip(text, room[0]);
        room[0] -= clipped.length();
        p.append(title).append(":\n").append(clipped).append("\n\n");
    }

    static String clip(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 40)) + "\n… (truncated to fit the request limit)";
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
