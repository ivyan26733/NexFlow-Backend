package com.nexflow.nexflow_backend.model.nco;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexflow.nexflow_backend.NcoMeta;
import com.nexflow.nexflow_backend.engine.FlowGraph;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything one flow run knows: per-node results, flow variables and the {@code nex} container.
 * Serialised into Execution.ncoSnapshot when the run ends (fields marked @JsonIgnore are runtime-only).
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NexflowContextObject {

    private NcoMeta meta;

    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();

    /** Keyed by node UUID only — serialised to DB/API so the transaction page shows each node once. */
    @Builder.Default
    private Map<String, NodeContext> nodes = new LinkedHashMap<>();

    /** Label-keyed aliases (calculateDiscount, start…) used for {{nodes.label.x}} resolution. Not persisted. */
    @JsonIgnore
    @Builder.Default
    private Map<String, NodeContext> nodeAliases = new LinkedHashMap<>();

    /** Universal flat container for named outputs; resolve via {{nex.NAME.field}}. Keys are case-sensitive. */
    @Builder.Default
    private Map<String, Object> nex = new LinkedHashMap<>();

    /** Execution order (a node appears once per run, so loop bodies appear several times). */
    @Builder.Default
    private List<String> nodeExecutionOrder = new ArrayList<>();

    /** The flow being executed. Read-only; shared by branch contexts. */
    @JsonIgnore
    private FlowGraph graph;

    /** Active LOOP nodes, innermost last. Each context (main / branch) has its own. */
    @JsonIgnore
    @Builder.Default
    private Map<String, LoopState> loopStates = new LinkedHashMap<>();

    /**
     * nex keys that automatic label keys must not overwrite: explicit "Save output as" names
     * and the trigger payload fields.
     */
    @JsonIgnore
    @Builder.Default
    private Set<String> protectedNexKeys = new HashSet<>();

    public static NexflowContextObject create(String flowId, String executionId, UUID userId) {
        return NexflowContextObject.builder()
                .meta(NcoMeta.builder()
                        .flowId(flowId)
                        .executionId(executionId)
                        .userId(userId)
                        .startedAt(Instant.now())
                        .status(ExecutionStatus.RUNNING)
                        .build())
                .build();
    }

    /**
     * Isolated context for one FORK branch: shares meta and graph, starts from copies of the parent's
     * variables, node results and nex, and has its own loop state so branch threads never share mutable maps.
     */
    public static NexflowContextObject forBranch(NexflowContextObject parent) {
        return NexflowContextObject.builder()
                .meta(parent.getMeta())
                .variables(new LinkedHashMap<>(parent.getVariables()))
                .nodes(new LinkedHashMap<>(parent.getNodes()))
                .nodeAliases(new LinkedHashMap<>(parent.getNodeAliases()))
                .nex(new LinkedHashMap<>(parent.getNex()))
                .graph(parent.getGraph())
                .protectedNexKeys(new HashSet<>(parent.getProtectedNexKeys()))
                .build();
    }

    /** Store by UUID — only this map is serialised. */
    public void setNodeOutput(String nodeId, NodeContext context) {
        nodes.put(nodeId, context);
    }

    /** Store by label alias — in-memory only. */
    public void setNodeAlias(String aliasKey, NodeContext context) {
        nodeAliases.put(aliasKey, context);
    }

    /** Resolve by UUID first, then by label alias. */
    public NodeContext getNodeOutput(String key) {
        NodeContext byId = nodes.get(key);
        return byId != null ? byId : nodeAliases.get(key);
    }

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    public Object getVariable(String key) {
        return variables.get(key);
    }

    /** The innermost loop currently repeating, or null. Backs {{loop.index}} inside loop bodies. */
    @JsonIgnore
    public LoopState getInnermostLoop() {
        LoopState last = null;
        for (LoopState s : loopStates.values()) last = s;
        return last;
    }

    /**
     * Data handed to SCRIPT and DECISION(code) nodes:
     * {@code { nex, input: { variables, nodes, trigger, nex, loop } }}.
     * {@code nex.x} is the preferred syntax; {@code input.*} is kept for older scripts.
     */
    @JsonIgnore
    public Map<String, Object> toScriptData() {
        Map<String, Object> allNodes = new LinkedHashMap<>(nodes);
        allNodes.putAll(nodeAliases);
        NodeContext start = getNodeOutput("start");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("variables", variables);
        input.put("nodes", allNodes);
        input.put("trigger", start != null ? start.getOutput() : null);
        input.put("nex", nex);
        LoopState loop = getInnermostLoop();
        if (loop != null) input.put("loop", Map.of("index", loop.getIndex()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nex", nex);
        data.put("input", input);
        return data;
    }
}
