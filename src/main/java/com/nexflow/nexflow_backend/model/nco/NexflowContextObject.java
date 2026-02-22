package com.nexflow.nexflow_backend.model.nco;

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

    // Keyed by nodeId, stores each node's execution result — LinkedHashMap preserves execution order
    @Builder.Default
    private Map<String, NodeContext> nodes = new LinkedHashMap<>();

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

    public void setNodeOutput(String nodeId, NodeContext context) {
        nodes.put(nodeId, context);
    }

    public NodeContext getNodeOutput(String nodeId) {
        return nodes.get(nodeId);
    }

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    public Object getVariable(String key) {
        return variables.get(key);
    }
}
