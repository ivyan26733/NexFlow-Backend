package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class VariableExecutor implements NodeExecutor {

    private final ReferenceResolver resolver;

    @Override
    public NodeType supportedType() {
        return NodeType.VARIABLE;
    }

    /*
     * Config shape:
     * {
     *   "variables": {
     *     "userId": "static-value",
     *     "userPlan": "{{nodes.pulse_001.successOutput.body.plan}}"
     *   }
     * }
     */
    @Override
    @SuppressWarnings("unchecked")
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> variableDefs = (Map<String, Object>) node.getConfig().getOrDefault("variables", new HashMap<>());

        Map<String, Object> resolved = resolver.resolveMap(variableDefs, nco);
        resolved.forEach((key, value) -> nco.setVariable(key, normalizeVariableValue(value)));

        Map<String, Object> output = new HashMap<>();
        resolved.forEach((key, value) -> output.put(key, normalizeVariableValue(value)));

        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(NodeType.VARIABLE.name())
                .status(NodeStatus.SUCCESS)
                .output(output)
                .build();
    }

    /**
     * Preserve numeric types so {{variables.a + variables.b}} does numeric addition.
     * Numeric strings (e.g. "10", "20.5") are converted to Integer/Long/Double.
     */
    private static Object normalizeVariableValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return value;
        if (!(value instanceof String s) || s.isEmpty()) return value;
        String t = s.trim();
        if (t.isEmpty()) return value;
        if (t.matches("-?\\d+")) {
            try {
                return Integer.parseInt(t);
            } catch (NumberFormatException e) {
                return Long.parseLong(t);
            }
        }
        if (t.matches("-?\\d+\\.\\d*") || t.matches("-?\\d*\\.\\d+")) {
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }
}
