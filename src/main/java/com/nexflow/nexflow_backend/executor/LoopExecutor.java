package com.nexflow.nexflow_backend.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.LoopState;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.repository.NodeExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LOOP: "while condition is true, run the body again".
 *
 * Each time the engine reaches the loop it evaluates the condition, where {{loop.index}} is the number
 * of passes already started (0 the first time). True → CONTINUE (the engine re-runs the body through the
 * Repeat connection, and the body leads back here). False → SUCCESS (the engine follows the Exit connection).
 *
 * Config: { "condition": "{{loop.index}} < 3", "maxIterations": 100 }
 * Output on exit: { iterationCount, index, accumulated } — accumulated holds the output of the
 * last body step of every pass (up to 100). Inside the body, {{loop.index}} is the current pass (0-based).
 */
@Component
@RequiredArgsConstructor
public class LoopExecutor implements NodeExecutor {

    private static final int DEFAULT_MAX = 100;
    private static final int HARD_MAX = 1000;
    private static final String[] OPERATORS = {"==", "!=", "<=", ">=", "<", ">"};

    private final ReferenceResolver resolver;
    private final ObjectMapper objectMapper;

    @Override
    public NodeType supportedType() {
        return NodeType.LOOP;
    }

    @Override
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        String nodeId = node.getId().toString();
        String condition = String.valueOf(node.configValue("condition", "false"));
        int max = node.configValue("maxIterations", null) instanceof Number n
                ? Math.max(1, Math.min(HARD_MAX, n.intValue()))
                : DEFAULT_MAX;

        LoopState state = nco.getLoopStates().computeIfAbsent(nodeId, LoopState::new);
        if (state.getIndex() > 0) collectPreviousPass(state, nodeId, nco);

        boolean repeat;
        try {
            repeat = evaluate(resolver.resolve(condition, nco, state));
        } catch (RuntimeException e) {
            nco.getLoopStates().remove(nodeId);
            return failure(nodeId, "The loop condition couldn't be checked: \"" + condition + "\".", e.getMessage());
        }

        if (repeat && state.getIndex() >= max) {
            nco.getLoopStates().remove(nodeId);
            return failure(nodeId, "The loop '" + node.displayName() + "' repeated " + max
                    + " times (its limit) and its condition was still true. Check the condition, or raise Max iterations.", null);
        }

        if (repeat) {
            Map<String, Object> pass = Map.of("index", state.getIndex(), "iteration", state.getIndex() + 1);
            state.setIndex(state.getIndex() + 1);
            return NodeContext.builder()
                    .nodeId(nodeId)
                    .nodeType(NodeType.LOOP.name())
                    .status(NodeStatus.CONTINUE)
                    .output(pass)
                    .build();
        }

        nco.getLoopStates().remove(nodeId); // so the loop starts fresh if it is entered again
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("iterationCount", state.getIndex());
        result.put("index", state.getIndex());
        result.put("accumulated", state.getAccumulated());
        String saveAs = node.saveOutputAsKey();
        if (saveAs != null) nco.getNex().put(saveAs, result);
        return NodeContext.builder()
                .nodeId(nodeId)
                .nodeType(NodeType.LOOP.name())
                .status(NodeStatus.SUCCESS)
                .successOutput(result)
                .output(result)
                .build();
    }

    /** We are coming back from the body: keep the output of its last step. */
    private void collectPreviousPass(LoopState state, String loopId, NexflowContextObject nco) {
        List<String> order = nco.getNodeExecutionOrder();
        if (order.isEmpty() || order.get(order.size() - 1).equals(loopId)) return;
        NodeContext last = nco.getNodeOutput(order.get(order.size() - 1));
        if (last == null) return;
        Object value = last.getSuccessOutput() != null ? last.getSuccessOutput() : last.getOutput();
        // Deep copy so later passes can't mutate what was already collected.
        state.accumulate(value != null ? objectMapper.convertValue(value, Object.class) : null);
    }

    /** "left op right" with numeric comparison when both sides are numbers, otherwise text; or a bare true/false. */
    static boolean evaluate(String condition) {
        String c = condition == null ? "" : condition.trim();
        for (String op : OPERATORS) {
            int i = c.indexOf(op);
            if (i >= 0) return compare(c.substring(0, i).trim(), c.substring(i + op.length()).trim(), op);
        }
        return Boolean.parseBoolean(c);
    }

    private static boolean compare(String left, String right, String op) {
        Double l = number(left);
        Double r = number(right);
        int cmp = l != null && r != null ? Double.compare(l, r) : left.compareTo(right);
        return switch (op) {
            case "==" -> cmp == 0;
            case "!=" -> cmp != 0;
            case "<" -> cmp < 0;
            case ">" -> cmp > 0;
            case "<=" -> cmp <= 0;
            case ">=" -> cmp >= 0;
            default -> false;
        };
    }

    private static Double number(String s) {
        try {
            return s.isBlank() ? null : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static NodeContext failure(String nodeId, String message, String detail) {
        return NodeContext.builder()
                .nodeId(nodeId)
                .nodeType(NodeType.LOOP.name())
                .status(NodeStatus.FAILURE)
                .failureOutput(Map.of("error", message))
                .errorMessage(message)
                .errorDetail(detail != null ? detail : message)
                .build();
    }
}
