package com.nexflow.nexflow_backend.executor;


import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class DecisionExecutor implements NodeExecutor {

    private final ReferenceResolver resolver;

    @Override
    public NodeType supportedType() {
        return NodeType.DECISION;
    }

    /*
     * Config shape:
     * {
     *   "left":     "{{variables.amount}}",
     *   "operator": "GT",              -- GT, LT, EQ, NEQ, GTE, LTE, CONTAINS
     *   "right":    "500"
     * }
     *
     * Result stored in output.result = true/false
     * Engine routes SUCCESS edge on true, FAILURE edge on false
     */
    @Override
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> config = node.getConfig();

        String left     = resolver.resolve((String) config.get("left"), nco);
        String operator = (String) config.get("operator");
        String right    = resolver.resolve((String) config.get("right"), nco);

        boolean result = evaluate(left, operator, right);

        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(NodeType.DECISION.name())
                .status(result ? NodeStatus.SUCCESS : NodeStatus.FAILURE)
                .output(Map.of("result", result, "left", left, "operator", operator, "right", right))
                .build();
    }

    private boolean evaluate(String left, String operator, String right) {
        try {
            double l = Double.parseDouble(left);
            double r = Double.parseDouble(right);
            return switch (operator.toUpperCase()) {
                case "GT"  -> l > r;
                case "LT"  -> l < r;
                case "GTE" -> l >= r;
                case "LTE" -> l <= r;
                case "EQ"  -> l == r;
                case "NEQ" -> l != r;
                default    -> false;
            };
        } catch (NumberFormatException e) {
            // Fall back to string comparison
            return switch (operator.toUpperCase()) {
                case "EQ"       -> left.equals(right);
                case "NEQ"      -> !left.equals(right);
                case "CONTAINS" -> left.contains(right);
                default         -> false;
            };
        }
    }
}
