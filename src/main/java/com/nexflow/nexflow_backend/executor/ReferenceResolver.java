package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReferenceResolver {

    // Matches {{nodes.nodeId.output.field}} or {{variables.key}} or {{meta.field}}
    private static final Pattern REF_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    // Resolves all {{ref}} expressions in a string against the current NCO
    public String resolve(String template, NexflowContextObject nco) {
        if (template == null || !template.contains("{{")) return template;

        Matcher matcher = REF_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String path = matcher.group(1).trim();
            Object value = resolvePath(path, nco);
            matcher.appendReplacement(result, value != null ? value.toString() : "");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    // Resolves an entire map of config values — each value can be a {{ref}}
    public Map<String, Object> resolveMap(Map<String, Object> config, NexflowContextObject nco) {
        if (config == null) return new HashMap<>();

        Map<String, Object> resolved = new HashMap<>();
        config.forEach((key, value) -> {
            if (value instanceof String s) {
                resolved.put(key, resolve(s, nco));
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
            NodeContext nodeCtx = nco.getNodeOutput(parts[1]);
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
