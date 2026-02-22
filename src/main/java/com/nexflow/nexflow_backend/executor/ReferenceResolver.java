package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReferenceResolver {

    private static final Logger log = LoggerFactory.getLogger(ReferenceResolver.class);

    // Matches {{nodes.nodeId.output.field}} or {{variables.key}} or {{meta.field}}
    private static final Pattern REF_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    // Resolves all {{ref}} expressions in a string against the current NCO.
    // Supports simple expressions: {{variables.a + variables.b}} or {{variables.x - 1}}
    public String resolve(String template, NexflowContextObject nco) {
        if (template == null || !template.contains("{{")) return template;

        Matcher matcher = REF_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String path = matcher.group(1).trim();
            Object value = resolvePathOrExpression(path, nco);
            if (value == null && (path.startsWith("nodes.") || path.startsWith("variables."))) {
                log.warn("Reference resolved to null: {{}} — check path and that START output.body is set", path);
            }
            matcher.appendReplacement(result, value != null ? value.toString() : "");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Object resolvePathOrExpression(String path, NexflowContextObject nco) {
        String op = null;
        int opIndex = -1;
        int opLength = 0;
        for (String candidate : new String[] { " + ", " - ", " * ", " / " }) {
            int i = path.indexOf(candidate);
            if (i >= 0) {
                op = candidate.trim();
                opIndex = i;
                opLength = candidate.length();
                break;
            }
        }
        if (op != null && opIndex >= 0) {
            String leftStr = path.substring(0, opIndex).trim();
            String rightStr = path.substring(opIndex + opLength).trim();
            Object left = resolvePath(leftStr, nco);
            Object right = resolvePath(rightStr, nco);
            return evaluateOp(op, left, right);
        }
        return resolvePath(path, nco);
    }

    private Object evaluateOp(String op, Object left, Object right) {
        if ("+".equals(op)) {
            if (left instanceof Number && right instanceof Number) {
                double sum = ((Number) left).doubleValue() + ((Number) right).doubleValue();
                return wholeNumber(sum);
            }
            return (left != null ? left.toString() : "") + (right != null ? right.toString() : "");
        }
        if ("-".equals(op) || "*".equals(op) || "/".equals(op)) {
            double l = toDouble(left);
            double r = toDouble(right);
            if (Double.isNaN(l) || Double.isNaN(r)) return "";
            double result = switch (op) {
                case "-" -> l - r;
                case "*" -> l * r;
                case "/" -> (r == 0) ? Double.NaN : l / r;
                default -> Double.NaN;
            };
            return Double.isNaN(result) ? "" : wholeNumber(result);
        }
        return "";
    }

    private static Object wholeNumber(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return (long) d;
        }
        return d;
    }

    private double toDouble(Object o) {
        if (o == null) return Double.NaN;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // Resolves an entire map of config values — each value can be a {{ref}} or expression.
    // When the value is a single {{...}} expression, the resolved Object (e.g. Number) is stored
    // so Mapper output keeps numeric types (e.g. result: 30 instead of "30").
    public Map<String, Object> resolveMap(Map<String, Object> config, NexflowContextObject nco) {
        if (config == null) return new HashMap<>();

        Map<String, Object> resolved = new HashMap<>();
        config.forEach((key, value) -> {
            if (value instanceof String s) {
                String trimmed = s.trim();
                if (trimmed.length() >= 5 && trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
                    String inner = trimmed.substring(2, trimmed.length() - 2).trim();
                    Object obj = resolvePathOrExpression(inner, nco);
                    resolved.put(key, obj != null ? obj : "");
                } else {
                    resolved.put(key, resolve(s, nco));
                }
            } else {
                resolved.put(key, value);
            }
        });
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Object resolvePath(String path, NexflowContextObject nco) {
        String[] parts = path.split("\\.");

        if (parts[0].equals("variables") && parts.length == 2) {
            return nco.getVariable(parts[1]);
        }

        if (parts[0].equals("meta") && parts.length == 2) {
            return resolveMetaField(parts[1], nco);
        }

        if (parts[0].equals("nodes") && parts.length >= 3) {
            String nodeKey = parts[1];
            if ("start".equalsIgnoreCase(nodeKey)) {
                nodeKey = findStartNodeId(nco);
                if (nodeKey == null) return null;
            }
            NodeContext nodeCtx = nco.getNodeOutput(nodeKey);
            if (nodeCtx == null) return null;

            // Navigate into the node's output map
            Map<String, Object> current = getOutputMap(nodeCtx, parts[2]);
            for (int i = 3; i < parts.length && current != null; i++) {
                Object next = current.get(parts[i]);
                if (i == parts.length - 1) return next;
                current = (next instanceof Map) ? (Map<String, Object>) next : null;
            }
            return current;
        }

        return null;
    }

    private String findStartNodeId(NexflowContextObject nco) {
        if (nco.getNodes() == null) return null;
        for (Map.Entry<String, NodeContext> e : nco.getNodes().entrySet()) {
            if (e.getValue() != null && "START".equals(e.getValue().getNodeType())) {
                return e.getKey();
            }
        }
        return null;
    }

    private Map<String, Object> getOutputMap(NodeContext ctx, String outputType) {
        return switch (outputType) {
            case "successOutput" -> ctx.getSuccessOutput();
            case "failureOutput" -> ctx.getFailureOutput();
            case "output"        -> ctx.getOutput();
            default              -> null;
        };
    }

    private Object resolveMetaField(String field, NexflowContextObject nco) {
        return switch (field) {
            case "flowId"      -> nco.getMeta().getFlowId();
            case "executionId" -> nco.getMeta().getExecutionId();
            case "startedAt"   -> nco.getMeta().getStartedAt();
            default            -> null;
        };
    }
}
