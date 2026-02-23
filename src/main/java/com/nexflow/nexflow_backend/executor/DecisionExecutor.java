package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.engine.ScriptRunner;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes DECISION nodes.
 *
 * Two modes — set by config.mode:
 *
 *   "simple" (default) — dropdown-based: left op right
 *   Config: { "left": "{{variables.amount}}", "operator": "GT", "right": "500" }
 *
 *   "code" — user writes a script that returns true/false
 *   Config: { "mode": "code", "language": "javascript", "code": "return input.variables.amount > 500" }
 *
 * Result: SUCCESS edge if condition is true, FAILURE edge if false.
 */
@Component
@RequiredArgsConstructor
public class DecisionExecutor implements NodeExecutor {

    private final ReferenceResolver resolver;
    private final ScriptRunner      scriptRunner;

    @Override
    public NodeType supportedType() {
        return NodeType.DECISION;
    }

    @Override
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        String mode = (String) node.getConfig().getOrDefault("mode", "simple");

        return "code".equals(mode)
                ? executeCodeMode(node, nco)
                : executeSimpleMode(node, nco);
    }

    // ── Simple mode ───────────────────────────────────────────────────────────

    private NodeContext executeSimpleMode(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> config = node.getConfig();

        String left     = resolver.resolve((String) config.getOrDefault("left", ""),     nco);
        String operator = (String) config.getOrDefault("operator", "EQ");
        String right    = resolver.resolve((String) config.getOrDefault("right", ""),    nco);

        boolean result  = evaluate(left, operator, right);

        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(NodeType.DECISION.name())
                .status(result ? NodeStatus.SUCCESS : NodeStatus.FAILURE)
                .output(Map.of("result", result, "left", left, "operator", operator, "right", right, "mode", "simple"))
                .build();
    }

    private boolean evaluate(String left, String operator, String right) {
        // Try numeric comparison first
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

    // ── Code mode ─────────────────────────────────────────────────────────────

    /**
     * Runs user code and expects a boolean return value.
     * true  → SUCCESS edge
     * false → FAILURE edge
     * error → FAILURE edge (with error message)
     */
    private NodeContext executeCodeMode(FlowNode node, NexflowContextObject nco) {
        String nodeId   = node.getId().toString();
        Map<String, Object> config = node.getConfig();

        String language = (String) config.getOrDefault("language", "javascript");
        String code     = (String) config.getOrDefault("code", "");

        if (code.isBlank()) {
            return decisionResult(nodeId, false, "Decision node in code mode has no code written.", "code");
        }

        // Reuse the same input shape as SCRIPT nodes for consistency
        Map<String, Object> scriptInput = buildInput(nco);
        ScriptRunner.ScriptResult result = scriptRunner.run(language, code, scriptInput);

        if (!result.success()) {
            return decisionResult(nodeId, false, result.error(), "code");
        }

        // Coerce the return value to boolean
        boolean decision = toBoolean(result.output());
        return decisionResult(nodeId, decision, null, "code");
    }

    private boolean toBoolean(Object value) {
        if (value == null)            return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number)  return ((Number) value).doubleValue() != 0;
        if (value instanceof String)  return !((String) value).isBlank() && !"false".equalsIgnoreCase((String) value);
        return true; // non-null objects are truthy
    }

    private Map<String, Object> buildInput(NexflowContextObject nco) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("variables", nco.getVariables());
        input.put("nodes", nco.getNodesForScriptInput());
        NodeContext startCtx = nco.getNodeOutput("start");
        Object triggerOutput = startCtx != null ? startCtx.getOutput() : null;
        input.put("trigger", triggerOutput);
        return input;
    }

    private NodeContext decisionResult(String nodeId, boolean decision, String error, String mode) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("result", decision);
        output.put("mode",   mode);
        if (error != null) output.put("error", error);

        return NodeContext.builder()
                .nodeId(nodeId)
                .nodeType(NodeType.DECISION.name())
                .status(decision ? NodeStatus.SUCCESS : NodeStatus.FAILURE)
                .output(output)
                .errorMessage(error)
                .build();
    }
}
