package com.nexflow.nexflow_backend.model.nco;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexflow.nexflow_backend.NcoMeta;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NexflowContextObject {

    private NcoMeta meta;

    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();

    /** Keyed by node UUID only — serialized to DB/API so Transactions page shows each node once. */
    @Builder.Default
    private Map<String, NodeContext> nodes = new LinkedHashMap<>();

    /** Label-keyed aliases (calculateDiscount, start, etc.) for resolution during execution only. Not persisted. */
    @JsonIgnore
    @Builder.Default
    private Map<String, NodeContext> nodeAliases = new LinkedHashMap<>();

    /** Universal flat container for named outputs: nodes with saveOutputAs store output here; resolve via {{nex.NAME.field}}. Keys are case-sensitive. */
    @Builder.Default
    private Map<String, Object> nex = new LinkedHashMap<>();

    // Explicit execution order — jsonb reorders object keys, so we keep a list (arrays stay ordered)
    @Builder.Default
    private List<String> nodeExecutionOrder = new ArrayList<>();

//    Factory Method
    public static NexflowContextObject create(String flowId, String executionId) {
        return NexflowContextObject.builder()
                .meta(NcoMeta.builder()
                        .flowId(flowId)
                        .executionId(executionId)
                        .startedAt(Instant.now())
                        .status(ExecutionStatus.RUNNING)
                        .build())
                .build();
    }

    /** Store by UUID — only this map is serialized to DB/API. */
    public void setNodeOutput(String nodeId, NodeContext context) {
        nodes.put(nodeId, context);
    }

    /** Store by label alias — in-memory only, not persisted. Used for {{nodes.calculateDiscount.x}} resolution. */
    public void setNodeAlias(String aliasKey, NodeContext context) {
        nodeAliases.put(aliasKey, context);
    }

    /** Resolve by UUID first, then by label alias. Used by ReferenceResolver and script input. */
    public NodeContext getNodeOutput(String key) {
        NodeContext byId = nodes.get(key);
        if (byId != null) return byId;
        return nodeAliases.get(key);
    }

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    public Object getVariable(String key) {
        return variables.get(key);
    }

    /** Merged view of nodes + nodeAliases for script input so input.nodes.calculateDiscount works. Not persisted. */
    @JsonIgnore
    public Map<String, NodeContext> getNodesForScriptInput() {
        Map<String, NodeContext> merged = new LinkedHashMap<>();
        if (nodes != null) merged.putAll(nodes);
        if (nodeAliases != null) merged.putAll(nodeAliases);
        return merged;
    }
}
