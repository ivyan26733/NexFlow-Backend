package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.LoopState;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes LOOP nodes: evaluates condition (with {{loop.index}} / {{loop.accumulated}}),
 * returns CONTINUE to re-enter loop body or SUCCESS to exit.
 */
@Component
@RequiredArgsConstructor
public class LoopExecutor implements NodeExecutor {

    private final ReferenceResolver resolver;
    private final ObjectMapper objectMapper;

    @Override
    public NodeType supportedType() {
        return NodeType.LOOP;
    }

    @Override
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        String nodeId = node.getId().toString();
        String label = node.getLabel() != null ? node.getLabel() : nodeId;

        // Fail fast if no CONTINUE edge (engine populates loopNodeHasContinueEdge at start)
        Boolean hasContinue = nco.getMeta().getLoopNodeHasContinueEdge() != null
            ? nco.getMeta().getLoopNodeHasContinueEdge().get(nodeId)
            : null;
        if (!Boolean.TRUE.equals(hasContinue)) {
            nco.getMeta().setErrorMessage(
                "LOOP node '" + label + "' has no CONTINUE edge. Draw an edge from the CONTINUE handle back to the loop body."
            );
            return failure(nodeId, nco.getMeta().getErrorMessage());
        }

        Map<String, Object> config = node.getConfig() != null ? node.getConfig() : Map.of();
        String condition = (String) config.getOrDefault("condition", "false");
        int maxIterations = Math.min(1000, Math.max(1, config.get("maxIterations") instanceof Number n
            ? n.intValue() : 100));

        Map<String, LoopState> loopStates = nco.getMeta().getLoopStates();
        if (loopStates == null) {
            loopStates = new LinkedHashMap<>();
            nco.getMeta().setLoopStates(loopStates);
        }
        LoopState loopState = loopStates.get(nodeId);
        if (loopState == null) {
            loopState = new LoopState(nodeId);
            loopState.setMaxIterations(maxIterations);
            loopStates.put(nodeId, loopState);
        } else {
            loopState.setMaxIterations(maxIterations);
        }

        if (loopState.getIndex() >= loopState.getMaxIterations()) {
            nco.getMeta().setErrorMessage(
                "Loop exceeded max iterations (" + maxIterations + ") at node '" + label + "'. Increase max or fix exit condition."
            );
            return failure(nodeId, nco.getMeta().getErrorMessage());
        }

        // Collect previous iteration output: last node in execution order before this LOOP run
        List<String> order = nco.getNodeExecutionOrder();
        if (order != null && !order.isEmpty()) {
            String lastNodeId = order.get(order.size() - 1);
            if (!lastNodeId.equals(nodeId)) {
                NodeContext lastCtx = nco.getNodeOutput(lastNodeId);
                if (lastCtx != null) {
                    Object previousOutput = lastCtx.getSuccessOutput() != null ? lastCtx.getSuccessOutput() : lastCtx.getOutput();
                    if (loopState.getAccumulated() == null) loopState.setAccumulated(new ArrayList<>());
                    Object deepCopy = deepCopy(previousOutput);
                    loopState.getAccumulated().add(deepCopy != null ? deepCopy : previousOutput);
                }
            }
        }

        String resolvedCondition = resolver.resolve(condition, nco, loopState);
        boolean conditionTrue;
        try {
            conditionTrue = evaluateCondition(resolvedCondition);
        } catch (Exception e) {
            nco.getMeta().setErrorMessage(
                "Loop condition failed: " + e.getMessage() + ". Condition was: '" + condition + "'"
            );
            return failure(nodeId, nco.getMeta().getErrorMessage());
        }

        if (conditionTrue) {
            loopState.setIndex(loopState.getIndex() + 1);
            return NodeContext.builder()
                .nodeId(nodeId)
                .nodeType(NodeType.LOOP.name())
                .status(NodeStatus.CONTINUE)
                .output(Map.of("index", loopState.getIndex(), "continuing", true))
                .build();
        }

        Map<String, Object> successOutput = new LinkedHashMap<>();
        successOutput.put("index", loopState.getIndex());
        successOutput.put("accumulated", loopState.getAccumulated() != null ? loopState.getAccumulated() : List.of());
        successOutput.put("iterationCount", loopState.getIndex() + 1);

        // Additionally store in nex if saveOutputAs is set for {{nex.myLoop.accumulated}} etc.
        String saveAs = extractSaveOutputAs(node);
        if (saveAs != null && !saveAs.isBlank()) {
            String key = saveAs.trim();
            if (key.matches("[a-zA-Z_][a-zA-Z0-9_]*") && nco.getNex() != null) {
                nco.getNex().put(key, new LinkedHashMap<>(successOutput));
            }
        }

        return NodeContext.builder()
            .nodeId(nodeId)
            .nodeType(NodeType.LOOP.name())
            .status(NodeStatus.SUCCESS)
            .successOutput(successOutput)
            .output(successOutput)
            .build();
    }

    private static String extractSaveOutputAs(FlowNode node) {
        try {
            Map<String, Object> config = node.getConfig();
            if (config == null) return null;
            Object val = config.get("saveOutputAs");
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse condition string: "left op right" with op in == != < > <= >=.
     * Numeric comparison if both sides parse as Double; else "true"/"false" as boolean; else string.
     */
    private boolean evaluateCondition(String condition) {
        if (condition == null) return false;
        condition = condition.trim();
        String[] ops = new String[] { "==", "!=", "<=", ">=", "<", ">" };
        for (String op : ops) {
            int i = condition.indexOf(op);
            if (i >= 0) {
                String left = condition.substring(0, i).trim();
                String right = condition.substring(i + op.length()).trim();
                return compare(left, right, op);
            }
        }
        return Boolean.parseBoolean(condition);
    }

    private boolean compare(String leftStr, String rightStr, String op) {
        Double lNum = parseDouble(leftStr);
        Double rNum = parseDouble(rightStr);
        if (lNum != null && rNum != null) {
            return switch (op) {
                case "==" -> lNum.equals(rNum);
                case "!=" -> !lNum.equals(rNum);
                case "<"  -> lNum < rNum;
                case ">"  -> lNum > rNum;
                case "<=" -> lNum <= rNum;
                case ">=" -> lNum >= rNum;
                default  -> false;
            };
        }
        if ("true".equalsIgnoreCase(leftStr) || "false".equalsIgnoreCase(leftStr)) {
            boolean l = Boolean.parseBoolean(leftStr);
            boolean r = Boolean.parseBoolean(rightStr);
            return switch (op) {
                case "==" -> l == r;
                case "!=" -> l != r;
                default   -> false;
            };
        }
        int cmp = Objects.equals(leftStr, rightStr) ? 0 : leftStr.compareTo(rightStr);
        return switch (op) {
            case "==" -> cmp == 0;
            case "!=" -> cmp != 0;
            case "<"  -> cmp < 0;
            case ">"  -> cmp > 0;
            case "<=" -> cmp <= 0;
            case ">=" -> cmp >= 0;
            default  -> false;
        };
    }

    private Double parseDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Deep copy via ObjectMapper so accumulated list does not hold references that can be mutated later. */
    private Object deepCopy(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(value), Object.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private NodeContext failure(String nodeId, String error) {
        return NodeContext.builder()
            .nodeId(nodeId)
            .nodeType(NodeType.LOOP.name())
            .status(NodeStatus.FAILURE)
            .failureOutput(Map.of("error", error != null ? error : "Loop failed"))
            .errorMessage(error)
            .build();
    }
}
