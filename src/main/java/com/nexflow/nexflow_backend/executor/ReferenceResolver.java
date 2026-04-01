package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.nco.LoopState;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
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
        return resolve(template, nco, null);
    }

    /** Same as resolve(template, nco) but with optional loop context for {{loop.index}} and {{loop.accumulated}}. */
    public String resolve(String template, NexflowContextObject nco, LoopState loopContext) {
        if (template == null || !template.contains("{{")) return template;

        Matcher matcher = REF_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String path = matcher.group(1).trim();
            Object value = resolvePathOrExpression(path, nco, loopContext);
            if (value == null && (path.startsWith("nodes.") || path.startsWith("variables."))) {
                log.warn("Reference resolved to null: {{}} — check path and that START output.body is set", path);
            }
            // Do not log for missing nex keys — optional references, case-sensitive
            matcher.appendReplacement(result, value != null ? value.toString() : "");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Object resolvePathOrExpression(String path, NexflowContextObject nco, LoopState loopContext) {
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
            Object left = resolvePath(leftStr, nco, loopContext);
            Object right = resolvePath(rightStr, nco, loopContext);
            return evaluateOp(op, left, right);
        }
        return resolvePath(path, nco, loopContext);
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
    public Map<String, Object> resolveMap(Map<String, Object> config, NexflowContextObject nco) {
        return resolveMap(config, nco, null);
    }

    /** Same as resolveMap(config, nco) but with optional loop context for {{loop.*}}. */
    public Map<String, Object> resolveMap(Map<String, Object> config, NexflowContextObject nco, LoopState loopContext) {
        if (config == null) return new HashMap<>();

        Map<String, Object> resolved = new HashMap<>();
        config.forEach((key, value) -> {
            if (value instanceof String s) {
                String trimmed = s.trim();
                if (trimmed.length() >= 5 && trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
                    String inner = trimmed.substring(2, trimmed.length() - 2).trim();
                    Object obj = resolvePathOrExpression(inner, nco, loopContext);
                    resolved.put(key, obj != null ? obj : "");
                } else {
                    resolved.put(key, resolve(s, nco, loopContext));
                }
            } else {
                resolved.put(key, value);
            }
        });
        return resolved;
    }

    /**
     * Resolve a path or {{path}} template to an Object (for AI node input bindings etc.).
     * Never logs or exposes credential paths; use for nex/nodes/variables only.
     */
    public Object resolveToObject(String pathOrTemplate, NexflowContextObject nco) {
        String path = pathOrTemplate;
        if (path != null && path.contains("{{") && path.contains("}}")) {
            int start = path.indexOf("{{");
            int end = path.indexOf("}}", start);
            if (start >= 0 && end > start) {
                path = path.substring(start + 2, end).trim();
            }
        }
        if (path == null || path.isBlank()) return null;
        return resolvePath(path, nco, null);
    }

    @SuppressWarnings("unchecked")
    private Object resolvePath(String path, NexflowContextObject nco, LoopState loopContext) {
        if (path != null && path.startsWith("input.")) {
            path = path.substring(6).trim();
        }
        if (path == null || path.isBlank()) return null;
        String[] parts = path.split("\\.");
        // nex.NAME.field — universal flat container; keys are case-sensitive (e.g. "user" != "User")
        if (parts[0].equals("nex") && parts.length >= 2) {
            String remainder = path.substring(4); // strip "nex."
            return resolveNestedPath(nco.getNex() != null ? nco.getNex() : Map.of(), remainder);
        }

        if (parts[0].equals("loop") && parts.length >= 2 && loopContext != null) {
            return switch (parts[1]) {
                case "index" -> loopContext.getIndex();
                case "accumulated" -> loopContext.getAccumulated();
                default -> null;
            };
        }

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

    // Matches a path segment that may carry a bracket index:
    //   "name"     → key="name",  index=null
    //   "items[0]" → key="items", index="0"
    //   "tags[12]" → key="tags",  index="12"
    private static final java.util.regex.Pattern BRACKET =
            java.util.regex.Pattern.compile("^([^\\[]+)(?:\\[(\\d+)\\])?$");

    /**
     * Walk a map/list tree by dot path (e.g. "user.result.userId").
     * Handles:
     * - Map lookups by key
     * - List lookups by numeric segment (legacy ".0" style)
     * - Bracket index notation "key[n]" for Lists stored under a Map key
     *
     * Returns null if any step is missing or incompatible with the expected structure.
     */
    @SuppressWarnings("unchecked")
    private Object resolveNestedPath(Object root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        String[] segments = path.split("\\.");
        Object current = root;

        for (String segment : segments) {
            if (current == null) return null;
            String seg = segment != null ? segment.trim() : "";
            if (seg.isEmpty()) return null;

            java.util.regex.Matcher m = BRACKET.matcher(seg);
            if (!m.matches()) {
                // Segment does not match "key" or "key[n]" — cannot resolve further
                return null;
            }

            String key    = m.group(1);   // map key or legacy numeric index
            String idxStr = m.group(2);   // optional [n] index

            // Step 1 — resolve the key from the current Map/List
            if (current instanceof Map<?, ?> map) {
                current = ((Map<String, Object>) map).get(key);
            } else if (current instanceof List<?> list && key.matches("\\d+")) {
                // Legacy ".0" dot-numeric style for lists
                int idx = Integer.parseInt(key);
                if (idx < 0 || idx >= list.size()) return null;
                current = list.get(idx);
            } else {
                return null;
            }

            // Step 2 — if bracket index present, apply it against the resulting List
            if (idxStr != null) {
                if (current == null) return null;
                int idx = Integer.parseInt(idxStr);

                if (current instanceof List<?> innerList) {
                    if (idx < 0 || idx >= innerList.size()) {
                        // Out-of-bounds index — treat as missing
                        return null;
                    }
                    current = innerList.get(idx);
                } else {
                    // Bracket used on non-List value — invalid structure
                    return null;
                }
            }
        }

        return current;
    }
}
