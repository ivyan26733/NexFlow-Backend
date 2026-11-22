package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.nco.LoopState;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
        if (template == null) return null;
        if (!template.contains("{{")) {
            // A field holding only a reference works without braces: "nex.order.total" == "{{nex.order.total}}".
            // If nothing is there it stays as typed, so a file name like "input.csv" is left alone.
            Object value = isWholeReference(template) ? resolvePath(template.trim(), nco, loopContext) : null;
            return value != null ? value.toString() : template;
        }

        Matcher matcher = REF_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String path = matcher.group(1).trim();
            Object value = resolvePathOrExpression(path, nco, loopContext);
            if (value == null && (path.startsWith("nodes.") || path.startsWith("variables."))) {
                log.warn("Reference resolved to null: {{}} — check path and that START output.body is set", path);
            }
            // Do not log for missing nex keys — optional references, case-sensitive
            matcher.appendReplacement(result, Matcher.quoteReplacement(value != null ? value.toString() : ""));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper TEXT_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Like {@link #resolve} but for human-facing text (chat messages, file contents): a reference to an
     * object or list is written as JSON instead of Java's {@code {a=1}} form.
     */
    public String resolveForText(String template, NexflowContextObject nco) {
        if (template == null) return null;
        if (!template.contains("{{")) {
            Object value = isWholeReference(template) ? resolvePath(template.trim(), nco, null) : null;
            return value != null ? asText(value) : template;
        }
        Matcher matcher = REF_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Object value = resolvePathOrExpression(matcher.group(1).trim(), nco, null);
            matcher.appendReplacement(result, Matcher.quoteReplacement(asText(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String asText(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return TEXT_JSON.writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }
        return value != null ? value.toString() : "";
    }

    /** One path segment: ".name", ".0", "[0]", or "[\"any key\"]" / "['any key']". */
    private static final String SEGMENT = "(?:\\.[^\\s.\\[\\]{}]+|\\[\\d+]|\\[\"[^\"]*\"]|\\['[^']*'])";
    private static final String PATH = "(?:nex|variables|nodes|loop|meta|input)" + SEGMENT + "+";
    private static final Pattern WHOLE_REF = Pattern.compile("\\s*" + PATH + "\\s*");

    /**
     * True when the whole value is a single reference written without braces, e.g. "nex.order.items[0].sku".
     * Text with anything else in it ("Total: nex.total") is not, so plain words are never rewritten.
     */
    public static boolean isWholeReference(String s) {
        return s != null && WHOLE_REF.matcher(s).matches();
    }

    /** "{{nex.x}}", "{{ nex.x }}" or "nex.x" — a field that holds exactly one reference. */
    public static boolean isSingleReference(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.startsWith("{{") && t.endsWith("}}") && t.indexOf("{{", 2) < 0) return true;
        return isWholeReference(t);
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
            Object left = operand(leftStr, nco, loopContext);
            Object right = operand(rightStr, nco, loopContext);
            return evaluateOp(op, left, right);
        }
        return resolvePath(path, nco, loopContext);
    }

    /** A side of "a + b": a number written in place ({{nex.qty * 2}}) or a path. */
    private Object operand(String s, NexflowContextObject nco, LoopState loopContext) {
        if (s.matches("-?\\d{1,15}(\\.\\d{1,15})?")) {
            return s.contains(".") ? (Object) Double.parseDouble(s) : (Object) Long.parseLong(s);
        }
        return resolvePath(s, nco, loopContext);
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
                    // Explicit {{reference}} syntax
                    String inner = trimmed.substring(2, trimmed.length() - 2).trim();
                    Object obj = resolvePathOrExpression(inner, nco, loopContext);
                    resolved.put(key, obj != null ? obj : "");
                } else if (isBareReference(trimmed)) {
                    // Bare reference path written without {{ }} — e.g. nex.mat.result.passRate
                    // Treat exactly the same as {{nex.mat.result.passRate}} so users don't need the braces
                    Object obj = resolvePathOrExpression(trimmed, nco, loopContext);
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
     * Returns true if the string is a bare reference path that should be resolved
     * without requiring {{ }} syntax. Matches paths starting with known NCO namespaces.
     * Examples: "nex.mat.result.passRate", "variables.userId", "nodes.start.output.body"
     */
    private static boolean isBareReference(String s) {
        return s.startsWith("nex.")
            || s.startsWith("variables.")
            || s.startsWith("nodes.")
            || s.startsWith("loop.")
            || s.startsWith("meta.");
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
        // nex.NAME.field — universal flat container; keys are case-sensitive (e.g. "user" != "User")
        if (path.startsWith("nex.") || path.startsWith("nex[")) {
            List<Object> segments = parseSegments(path.substring(3));
            return segments == null ? null : walkNex(nco.getNex() != null ? nco.getNex() : Map.of(), segments);
        }
        String[] parts = path.split("\\.");

        if (parts[0].equals("loop") && parts.length >= 2) {
            // In a LOOP's own condition, index = passes already started. Inside the loop body
            // (no explicit context) index = the current pass, 0-based.
            LoopState loop = loopContext != null ? loopContext : nco.getInnermostLoop();
            if (loop == null) return null;
            return switch (parts[1]) {
                case "index" -> loopContext != null ? loop.getIndex() : loop.getIndex() - 1;
                case "accumulated" -> loop.getAccumulated();
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

    /**
     * Splits what follows a root name into keys and list positions:
     * ".order.items[0][\"unit price\"]" → ["order", "items", 0, "unit price"]. Plain ".0" stays a key
     * (it still indexes a list, as before). Returns null when the path is malformed.
     */
    static List<Object> parseSegments(String rest) {
        List<Object> out = new ArrayList<>();
        int i = 0, n = rest.length();
        while (i < n) {
            char c = rest.charAt(i);
            if (c == '.') {
                int end = i + 1;
                while (end < n && rest.charAt(end) != '.' && rest.charAt(end) != '[') end++;
                String key = rest.substring(i + 1, end).trim();
                if (key.isEmpty()) return null;
                out.add(key);
                i = end;
            } else if (c == '[') {
                int close;
                if (i + 1 < n && (rest.charAt(i + 1) == '"' || rest.charAt(i + 1) == '\'')) {
                    char quote = rest.charAt(i + 1);
                    int endQuote = rest.indexOf(quote, i + 2);
                    if (endQuote < 0 || endQuote + 1 >= n || rest.charAt(endQuote + 1) != ']') return null;
                    out.add(new QuotedKey(rest.substring(i + 2, endQuote)));
                    close = endQuote + 1;
                } else {
                    close = rest.indexOf(']', i);
                    if (close < 0) return null;
                    String idx = rest.substring(i + 1, close).trim();
                    if (!idx.matches("\\d{1,9}")) return null;
                    out.add(Integer.parseInt(idx));
                }
                i = close + 1;
            } else {
                return null;
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** A key written in quotes, ["like this"]: always a map key, never a list position. */
    record QuotedKey(String key) {}

    /**
     * Walks nex. One shortcut at the step level: if a step's output has no such field, the step's
     * result (scripts, AI, sub-flows) or body (API calls) is tried, so nex.step.total finds
     * nex.step.result.total. Full paths keep working and always win.
     */
    private static Object walkNex(Map<String, Object> nex, List<Object> segments) {
        Object current = nex;
        for (int i = 0; i < segments.size(); i++) {
            Object seg = segments.get(i);
            Object next = step(current, seg);
            if (next == null && i == 1 && current instanceof Map<?, ?> stepOutput && !(seg instanceof Integer)) {
                for (String wrapper : new String[] { "result", "body" }) {
                    Object inner = stepOutput.get(wrapper);
                    if (inner instanceof Map<?, ?> && (next = step(inner, seg)) != null) break;
                }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    private static Object step(Object current, Object seg) {
        if (seg instanceof Integer idx) {
            return current instanceof List<?> list && idx < list.size() ? list.get(idx) : null;
        }
        String key = seg instanceof QuotedKey q ? q.key() : (String) seg;
        if (current instanceof Map<?, ?> map) return map.get(key);
        if (current instanceof List<?> list && !(seg instanceof QuotedKey) && key.matches("\\d{1,9}")) {
            int idx = Integer.parseInt(key);           // legacy ".0" style
            return idx < list.size() ? list.get(idx) : null;
        }
        return null;
    }
}
