package com.nexflow.nexflow_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.channel.ChannelService;
import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.model.domain.FlowEdge;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.model.domain.NexusConnector;
import com.nexflow.nexflow_backend.model.domain.UserRole;
import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import com.nexflow.nexflow_backend.repository.ExecutionRepository;
import com.nexflow.nexflow_backend.repository.FlowEdgeRepository;
import com.nexflow.nexflow_backend.repository.FlowNodeRepository;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import com.nexflow.nexflow_backend.repository.LlmProviderConfigRepository;
import com.nexflow.nexflow_backend.repository.NexusConnectorRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Read-only lookups the assistant can make over the user's own workspace. Everything is scoped to
 * what the user can access, and secrets (passwords, tokens, API keys) are redacted before the model
 * sees anything.
 *
 * The one "action" tool, propose_run_flow, doesn't run anything: it adds a button to the reply that
 * the user must click.
 */
@Component
public class AssistantTools {

    /** Each tool result is capped so one big flow or payload can't blow the model's context. */
    static final int MAX_RESULT_CHARS = 12_000;
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|token|api[_-]?key|authorization|credential|private[_-]?key|access[_-]?key).*");

    private final FlowRepository flows;
    private final FlowNodeRepository nodes;
    private final FlowEdgeRepository edges;
    private final ExecutionRepository executions;
    private final NexusConnectorRepository connectors;
    private final LlmProviderConfigRepository providers;
    private final GroupService groupService;
    private final ScheduleService scheduleService;
    private final ChannelService channelService;
    private final FileStorageService fileStorage;
    private final ObjectMapper mapper;

    public AssistantTools(FlowRepository flows, FlowNodeRepository nodes, FlowEdgeRepository edges,
                          ExecutionRepository executions, NexusConnectorRepository connectors,
                          LlmProviderConfigRepository providers, GroupService groupService,
                          ScheduleService scheduleService, ChannelService channelService,
                          FileStorageService fileStorage, ObjectMapper mapper) {
        this.flows = flows;
        this.nodes = nodes;
        this.edges = edges;
        this.executions = executions;
        this.connectors = connectors;
        this.providers = providers;
        this.groupService = groupService;
        this.scheduleService = scheduleService;
        this.channelService = channelService;
        this.fileStorage = fileStorage;
        this.mapper = mapper;
    }

    /** A button the frontend shows under the reply. */
    public record Action(String type, String label, String flowId, String flowName, Map<String, Object> payload) {}

    /** Tool descriptions given to the model. Keep in sync with {@link #call}. */
    public static final String CATALOG = """
            list_flows {}                                   — all flows the user can access: id, name, slug, status, runs/failures in the last 24h
            get_flow {"flow": "<id, name or slug>"}         — one flow's steps (type, label, config), connections, and its triggers (pulse URL, schedules, channels)
            list_executions {"flow"?: "...", "status"?: "SUCCESS|FAILURE|RUNNING", "hours"?: 24, "limit"?: 10}
                                                            — recent runs with status, trigger, duration and error message
            get_execution {"executionId": "<id>"}           — one run step by step: each step's status, error and output, plus the input it started with
            stats {"hours"?: 24}                            — totals, success rate, and the flows failing most
            list_schedules {}                               — cron schedules, next/last run
            list_channels {}                                — Slack / Telegram / WhatsApp / Discord connections, the flow each starts, setup problems and last error
            list_files {"limit"?: 20}                       — uploaded and generated files
            list_connectors {}                              — saved Nexus connectors (REST / JDBC), without secrets
            list_ai_providers {}                            — which AI providers have keys and are enabled
            propose_run_flow {"flow": "...", "payload"?: {}} — offer the user a "Run" button for a flow (it does NOT run it). Only when the user asks to run/test a flow.
            """;

    /** Runs a tool. Returns a JSON string (possibly an {"error": ...} object), never throws. */
    public String call(String tool, Map<String, Object> args, NexUser user, List<Action> actions) {
        Map<String, Object> a = args != null ? args : Map.of();
        try {
            Object result = switch (tool) {
                case "list_flows" -> listFlows(user);
                case "get_flow" -> getFlow(str(a.get("flow")), user);
                case "list_executions" -> listExecutions(a, user);
                case "get_execution" -> getExecution(str(a.get("executionId")), user);
                case "stats" -> stats(intArg(a.get("hours"), 24, 24 * 30), user);
                case "list_schedules" -> listSchedules(user);
                case "list_channels" -> listChannels(user);
                case "list_files" -> listFiles(intArg(a.get("limit"), 20, 50), user);
                case "list_connectors" -> listConnectors(user);
                case "list_ai_providers" -> listProviders(user);
                case "propose_run_flow" -> proposeRun(a, user, actions);
                default -> Map.of("error", "Unknown tool '" + tool + "'. Use one of the listed tools.");
            };
            return toJson(result);
        } catch (IllegalArgumentException e) {
            return toJson(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return toJson(Map.of("error", "The lookup failed: " + e.getMessage()));
        }
    }

    // ── Overview injected into every conversation ────────────────────────────

    /** A compact picture of the workspace so simple questions need no tool call at all. */
    public String overview(NexUser user) {
        Map<String, Object> o = new LinkedHashMap<>();
        List<Map<String, Object>> flowList = listFlows(user);
        o.put("flows", flowList.size() > 40 ? flowList.subList(0, 40) : flowList);
        if (flowList.size() > 40) o.put("moreFlows", flowList.size() - 40);
        o.put("last24h", stats(24, user));
        o.put("channels", channelService.list(user).size());
        o.put("schedules", scheduleService.list(user).size());
        o.put("aiProviders", listProviders(user));
        return toJson(o);
    }

    // ── Tools ────────────────────────────────────────────────────────────────

    List<Map<String, Object>> listFlows(NexUser user) {
        List<Flow> visible = accessibleFlows(user);
        Map<UUID, List<Execution>> recent = recentRuns(visible, 24);
        return visible.stream()
                .sorted(Comparator.comparing(Flow::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", f.getId().toString());
                    m.put("name", f.getName());
                    m.put("slug", f.getSlug());
                    m.put("status", f.getStatus().name());
                    if (f.getDescription() != null && !f.getDescription().isBlank()) m.put("description", trim(f.getDescription(), 200));
                    List<Execution> runs = recent.getOrDefault(f.getId(), List.of());
                    m.put("runs24h", runs.size());
                    m.put("failures24h", runs.stream().filter(e -> e.getStatus() == ExecutionStatus.FAILURE).count());
                    return m;
                }).toList();
    }

    Map<String, Object> getFlow(String ref, NexUser user) {
        Flow flow = findFlow(ref, user);
        List<FlowNode> flowNodes = nodes.findByFlowId(flow.getId());
        Map<UUID, String> labels = flowNodes.stream().collect(Collectors.toMap(FlowNode::getId, FlowNode::displayName, (x, y) -> x));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", flow.getId().toString());
        out.put("name", flow.getName());
        out.put("slug", flow.getSlug());
        out.put("status", flow.getStatus().name());
        out.put("steps", flowNodes.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", n.displayName());
            m.put("type", n.getNodeType().name());
            m.put("referenceKey", n.labelKey());
            if (n.getConfig() != null && !n.getConfig().isEmpty()) m.put("config", redact(n.getConfig()));
            return m;
        }).toList());
        out.put("connections", edges.findByFlowId(flow.getId()).stream().map((FlowEdge e) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", labels.getOrDefault(e.getSourceNodeId(), "?"));
            m.put("to", labels.getOrDefault(e.getTargetNodeId(), "?"));
            m.put("when", e.getConditionType() != null ? e.getConditionType().name() : "DEFAULT");
            return m;
        }).toList());

        Map<String, Object> triggers = new LinkedHashMap<>();
        triggers.put("pulse", "POST /api/pulse/" + (flow.getSlug() != null && !flow.getSlug().isBlank() ? flow.getSlug() : flow.getId())
                + (flow.getStatus().name().equals("ACTIVE") ? "" : " (only works once the flow is ACTIVE)"));
        triggers.put("schedules", scheduleService.list(user).stream()
                .filter(s -> s.flowId().equals(flow.getId().toString()))
                .map(s -> s.label() + " — " + s.cronExpression() + " " + s.timezone() + (s.enabled() ? "" : " (paused)")).toList());
        triggers.put("channels", channelService.list(user).stream()
                .filter(c -> flow.getId().toString().equals(c.flowId()))
                .map(c -> c.platform() + " '" + c.label() + "'" + (c.enabled() ? "" : " (paused)")).toList());
        out.put("triggers", triggers);
        out.put("studioLink", "/studio/" + flow.getId());
        return out;
    }

    List<Map<String, Object>> listExecutions(Map<String, Object> a, NexUser user) {
        int hours = intArg(a.get("hours"), 24, 24 * 90);
        int limit = intArg(a.get("limit"), 10, 25);
        String status = str(a.get("status"));
        List<Flow> scope = a.get("flow") != null && !str(a.get("flow")).isBlank()
                ? List.of(findFlow(str(a.get("flow")), user))
                : accessibleFlows(user);
        Map<UUID, String> names = scope.stream().collect(Collectors.toMap(Flow::getId, Flow::getName));
        if (names.isEmpty()) return List.of();
        Instant since = Instant.now().minus(Duration.ofHours(hours));
        return executions.findByFlowIdInAndStartedAtAfterOrderByStartedAtDesc(new ArrayList<>(names.keySet()), since).stream()
                .filter(e -> status == null || status.isBlank() || e.getStatus().name().equalsIgnoreCase(status))
                .limit(limit)
                .map(e -> summary(e, names.get(e.getFlowId())))
                .toList();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> getExecution(String id, NexUser user) {
        UUID executionId = uuid(id, "execution");
        Execution e = executions.findById(executionId).orElseThrow(() -> new IllegalArgumentException("No run with id " + id + "."));
        Flow flow = flows.findById(e.getFlowId()).orElseThrow(() -> new IllegalArgumentException("That run's flow was deleted."));
        if (!groupService.hasFlowAccess(flow.getId(), flow.getUserId(), user)) throw new IllegalArgumentException("You don't have access to that run.");

        Map<String, Object> out = summary(e, flow.getName());
        if (e.getErrorDetail() != null) out.put("errorDetail", trim(e.getErrorDetail(), 800));
        out.put("input", redact(e.getPayload() != null ? e.getPayload() : Map.of()));

        Map<String, String> labels = nodes.findByFlowId(flow.getId()).stream()
                .collect(Collectors.toMap(n -> n.getId().toString(), FlowNode::displayName, (x, y) -> x));
        Map<String, Object> snapshot = e.getNcoSnapshot() != null ? e.getNcoSnapshot() : Map.of();
        Map<String, Object> results = snapshot.get("nodes") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<Object> order = snapshot.get("nodeExecutionOrder") instanceof List<?> l ? (List<Object>) l : new ArrayList<>(results.keySet());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object nodeId : order.stream().distinct().toList()) {
            if (!(results.get(String.valueOf(nodeId)) instanceof Map<?, ?> raw)) continue;
            Map<String, Object> r = (Map<String, Object>) raw;
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", labels.getOrDefault(String.valueOf(nodeId), String.valueOf(r.get("nodeType"))));
            step.put("type", r.get("nodeType"));
            step.put("status", r.get("status"));
            if (r.get("errorMessage") != null) step.put("error", r.get("errorMessage"));
            if (r.get("errorDetail") != null) step.put("errorDetail", trim(String.valueOf(r.get("errorDetail")), 500));
            Object output = r.get("successOutput") != null ? r.get("successOutput") : r.get("failureOutput") != null ? r.get("failureOutput") : r.get("output");
            if (output != null) step.put("output", truncateJson(redact(output), 1_200));
            steps.add(step);
        }
        out.put("steps", steps);
        if (e.getStatus() == ExecutionStatus.RUNNING) out.put("note", "Still running (or stuck — runs lost on a server restart stay RUNNING; use Discard running on the Transactions page).");
        out.put("link", "/transactions/" + e.getId());
        return out;
    }

    Map<String, Object> stats(int hours, NexUser user) {
        List<Flow> visible = accessibleFlows(user);
        Map<UUID, String> names = visible.stream().collect(Collectors.toMap(Flow::getId, Flow::getName));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hours", hours);
        if (names.isEmpty()) {
            out.put("runs", 0);
            return out;
        }
        List<Execution> runs = executions.findByFlowIdInAndStartedAtAfterOrderByStartedAtDesc(
                new ArrayList<>(names.keySet()), Instant.now().minus(Duration.ofHours(hours)));
        long ok = runs.stream().filter(e -> e.getStatus() == ExecutionStatus.SUCCESS).count();
        long failed = runs.stream().filter(e -> e.getStatus() == ExecutionStatus.FAILURE).count();
        out.put("runs", runs.size());
        out.put("success", ok);
        out.put("failure", failed);
        out.put("running", runs.size() - ok - failed);
        if (ok + failed > 0) out.put("successRate", Math.round(ok * 1000.0 / (ok + failed)) / 10.0 + "%");
        out.put("mostFailing", runs.stream().filter(e -> e.getStatus() == ExecutionStatus.FAILURE)
                .collect(Collectors.groupingBy(Execution::getFlowId, Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<UUID, Long>comparingByValue().reversed()).limit(5)
                .map(en -> names.get(en.getKey()) + " (" + en.getValue() + ")").toList());
        return out;
    }

    List<Map<String, Object>> listSchedules(NexUser user) {
        return scheduleService.list(user).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", s.label());
            m.put("flow", s.flowName());
            m.put("flowStatus", s.flowStatus());
            m.put("cron", s.cronExpression());
            m.put("timezone", s.timezone());
            m.put("enabled", s.enabled());
            m.put("nextRunAt", s.nextRunAt());
            m.put("lastRunAt", s.lastRunAt());
            return m;
        }).toList();
    }

    List<Map<String, Object>> listChannels(NexUser user) {
        return channelService.list(user).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("platform", c.platform());
            m.put("label", c.label());
            m.put("startsFlow", c.flowName());
            m.put("flowStatus", c.flowStatus());
            m.put("enabled", c.enabled());
            m.put("missingCredentials", c.missing());
            m.put("webhookUrl", c.webhookUrl());
            m.put("messagesReceived", c.eventCount());
            m.put("lastEventAt", c.lastEventAt());
            if (c.lastError() != null) m.put("lastError", c.lastError());
            if (c.lastExecutionId() != null) m.put("lastRun", "/transactions/" + c.lastExecutionId());
            return m;
        }).toList();
    }

    List<Map<String, Object>> listFiles(int limit, NexUser user) {
        return fileStorage.listFor(user).stream().limit(limit).map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileId", f.getId().toString());
            m.put("name", f.getName());
            m.put("size", FileStorageService.humanSize(f.getSizeBytes()));
            m.put("source", f.getSource());
            m.put("createdAt", f.getCreatedAt());
            return m;
        }).toList();
    }

    List<Map<String, Object>> listConnectors(NexUser user) {
        return connectors.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> user.getRole() == UserRole.ADMIN || Objects.equals(c.getUserId(), user.getId()))
                .map((NexusConnector c) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId().toString());
                    m.put("name", c.getName());
                    m.put("type", c.getConnectorType());
                    if ("JDBC".equalsIgnoreCase(c.getConnectorType())) {
                        m.put("jdbcUrl", c.getJdbcUrl() != null ? c.getJdbcUrl().replaceAll("(?i)(password=)[^&;]*", "$1••••") : null);
                    } else {
                        m.put("baseUrl", c.getBaseUrl());
                        m.put("authType", c.getAuthType());
                    }
                    return m;
                }).toList();
    }

    List<Map<String, Object>> listProviders(NexUser user) {
        return providers.findByUserId(user.getId()).stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", p.getProvider().name());
            m.put("enabled", p.isEnabled());
            return m;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> proposeRun(Map<String, Object> a, NexUser user, List<Action> actions) {
        Flow flow = findFlow(str(a.get("flow")), user);
        Map<String, Object> payload = a.get("payload") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        actions.add(new Action("RUN_FLOW", "Run " + flow.getName(), flow.getId().toString(), flow.getName(), payload));
        return Map.of("proposed", true, "note", "A Run button for '" + flow.getName() + "' will appear under your reply. "
                + "Tell the user to click it; it runs " + (flow.getStatus().name().equals("ACTIVE") ? "" : "the draft ") + "with the payload shown.");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    List<Flow> accessibleFlows(NexUser user) {
        return flows.findAll().stream().filter(f -> groupService.hasFlowAccess(f.getId(), f.getUserId(), user)).toList();
    }

    /** By id, slug, exact name, then unique partial name. */
    Flow findFlow(String ref, NexUser user) {
        if (ref == null || ref.isBlank()) throw new IllegalArgumentException("Say which flow (id, name or slug).");
        String r = ref.trim();
        List<Flow> visible = accessibleFlows(user);
        for (Function<Flow, Boolean> match : List.<Function<Flow, Boolean>>of(
                f -> f.getId().toString().equalsIgnoreCase(r),
                f -> r.equalsIgnoreCase(f.getSlug()),
                f -> r.equalsIgnoreCase(f.getName()))) {
            List<Flow> hits = visible.stream().filter(match::apply).toList();
            if (hits.size() == 1) return hits.get(0);
        }
        String needle = r.toLowerCase(Locale.ROOT);
        List<Flow> partial = visible.stream().filter(f -> f.getName().toLowerCase(Locale.ROOT).contains(needle)).toList();
        if (partial.size() == 1) return partial.get(0);
        if (partial.isEmpty()) throw new IllegalArgumentException("No flow matches '" + ref + "'. Use list_flows to see them.");
        throw new IllegalArgumentException("Several flows match '" + ref + "': "
                + partial.stream().map(Flow::getName).limit(10).collect(Collectors.joining(", ")) + ". Be more specific.");
    }

    private Map<UUID, List<Execution>> recentRuns(List<Flow> visible, int hours) {
        if (visible.isEmpty()) return Map.of();
        return executions.findByFlowIdInAndStartedAtAfterOrderByStartedAtDesc(
                        visible.stream().map(Flow::getId).toList(), Instant.now().minus(Duration.ofHours(hours)))
                .stream().collect(Collectors.groupingBy(Execution::getFlowId));
    }

    private static Map<String, Object> summary(Execution e, String flowName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("executionId", e.getId().toString());
        m.put("flow", flowName);
        m.put("status", e.getStatus().name());
        m.put("triggeredBy", e.getTriggeredBy());
        m.put("startedAt", e.getStartedAt());
        if (e.getCompletedAt() != null && e.getStartedAt() != null) {
            m.put("durationMs", Duration.between(e.getStartedAt(), e.getCompletedAt()).toMillis());
        }
        if (e.getErrorMessage() != null) m.put("error", e.getErrorMessage());
        return m;
    }

    /** Replaces secret-looking values anywhere in a nested map/list. */
    @SuppressWarnings("unchecked")
    static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> {
                boolean secret = SECRET_KEY.matcher(k).matches() && !(v instanceof Map<?, ?>) && !(v instanceof List<?>)
                        && v != null && !String.valueOf(v).isBlank() && !String.valueOf(v).contains("{{");
                out.put(k, secret ? "••••" : redact(v));
            });
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(AssistantTools::redact).toList();
        if (value instanceof String s) {
            return s.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}", "$1••••")
                    .replaceAll("\\b(xox[abposr]-|sk-|sk-ant-)[A-Za-z0-9-_]{8,}", "$1••••");
        }
        return value;
    }

    private Object truncateJson(Object value, int max) {
        String json = toJson(value);
        return json.length() <= max ? value : json.substring(0, max) + "… (truncated)";
    }

    String toJson(Object value) {
        try {
            String json = mapper.writeValueAsString(value);
            return json.length() <= MAX_RESULT_CHARS ? json : json.substring(0, MAX_RESULT_CHARS) + "… (truncated)";
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    private static UUID uuid(String s, String what) {
        try {
            return UUID.fromString(s != null ? s.trim() : "");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + s + "' isn't a valid " + what + " id.");
        }
    }

    private static int intArg(Object o, int fallback, int max) {
        int v;
        try {
            v = o instanceof Number n ? n.intValue() : o != null ? Integer.parseInt(o.toString().trim()) : fallback;
        } catch (NumberFormatException e) {
            v = fallback;
        }
        return Math.max(1, Math.min(max, v));
    }
}
