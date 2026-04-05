package com.nexflow.nexflow_backend.executor.impl;

import com.nexflow.nexflow_backend.engine.ScriptRunner;
import com.nexflow.nexflow_backend.repository.NodeExecutor;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes SCRIPT nodes.
 *
 * Config shape:
 * {
 *   "language": "javascript",   // "javascript" or "python"
 *   "code":     "const items = nex.fetchOrders?.body?.items ?? []; return items.filter(i => i.active)"
 * }
 *
 * The script subprocess receives a JSON file containing:
 * {
 *   "nex":   { ...unified flat container: nex.userId, nex.fetchUser.body.items, nex.start.body... },
 *   "input": { ...legacy object: input.variables, input.nodes, input.trigger, input.nex (backward compat)... }
 * }
 *
 * Preferred (new) syntax:   nex.userId / nex.fetchUser.body.items
 * Legacy (still works):     input.variables.userId / input.nodes.fetchUser.successOutput.body
 *
 * JavaScript: use `return` to return a value.
 * Python: assign your final value to a variable named `result`.
 *
 * SUCCESS edge: script ran without error. Return value in successOutput.result
 * FAILURE edge: script threw an error. Error message in failureOutput.error
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScriptExecutor implements NodeExecutor {

    private final ScriptRunner scriptRunner;

    @Override
    public NodeType supportedType() {
        return NodeType.SCRIPT;
    }

    @Override
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        String nodeId   = node.getId().toString();
        Map<String, Object> config = node.getConfig();

        String language = (String) config.getOrDefault("language", "javascript");
        String code     = (String) config.getOrDefault("code", "");

        if (code.isBlank()) {
            return failure(nodeId, "SCRIPT node has no code. Open the node and write your script.");
        }

        // Per-node timeout: "timeoutSeconds" in config, capped at 300 by ScriptRunner.
        int timeoutSeconds = 10;
        Object timeoutCfg = config.get("timeoutSeconds");
        if (timeoutCfg instanceof Number n) {
            timeoutSeconds = n.intValue();
        }

        // Build the data passed to the script subprocess.
        // Structured as { nex: {...}, input: {...} } so scripts can use either:
        //   nex.userId            (new unified syntax)
        //   input.variables.userId (legacy backward-compat syntax)
        Map<String, Object> scriptData = new LinkedHashMap<>();
        scriptData.put("nex",   nco.getNex() != null ? nco.getNex() : new LinkedHashMap<>());
        scriptData.put("input", buildLegacyInput(nco));

        // Run it
        ScriptRunner.ScriptResult result = scriptRunner.run(language, code, scriptData, timeoutSeconds);

        if (result.success()) {
            Object output = result.output();
            boolean nullOutput = output == null || isNullOrEmptyResult(output);

            // If failOnNullOutput is true in node config, treat a null/undefined return as FAILURE.
            // This is useful for data-processing scripts that depend on nex keys which may be missing
            // when the trigger payload is empty. Add "failOnNullOutput": true to the script config in Studio.
            boolean failOnNull = Boolean.TRUE.equals(config.get("failOnNullOutput"));
            if (nullOutput && failOnNull) {
                log.warn("[ScriptExecutor] Node '{}' returned null/undefined and failOnNullOutput=true — marking FAILURE. " +
                        "Verify your nex references match the trigger payload keys.", node.getLabel());
                return failure(nodeId,
                        "Script returned null or undefined. Verify nex references match the trigger payload. " +
                        "Add validation: `if (nex.yourKey == null) throw new Error('yourKey is required');`");
            }

            if (nullOutput) {
                log.warn("[ScriptExecutor] Node '{}' returned null/undefined. " +
                        "If this is unexpected, check that nex keys referenced in the script are present in the trigger payload. " +
                        "To fail automatically on null output, add \\\"failOnNullOutput\\\": true to the script node config.",
                        node.getLabel());
            }

            Map<String, Object> successOutput = new LinkedHashMap<>();
            successOutput.put("result",   nullOutput ? null : output);
            successOutput.put("language", language);

            return NodeContext.builder()
                    .nodeId(nodeId)
                    .nodeType(NodeType.SCRIPT.name())
                    .status(NodeStatus.SUCCESS)
                    .input(Map.of("language", language, "codeLength", code.length()))
                    .successOutput(successOutput)
                    .build();
        } else {
            return failure(nodeId, result.error());
        }
    }

    /**
     * Builds the legacy `input` object for backward-compatible scripts that use input.variables.x, input.nodes.x, etc.
     * New scripts should use the top-level `nex` object instead.
     */
    private Map<String, Object> buildLegacyInput(NexflowContextObject nco) {
        Map<String, Object> input = new LinkedHashMap<>();

        // All flow variables set by VARIABLE nodes
        input.put("variables", nco.getVariables());

        // All previous node outputs (UUID + label keys) so input.nodes.calculateDiscount works
        input.put("nodes", nco.getNodesForScriptInput());

        // Shortcut to the original trigger payload (START node output)
        NodeContext startCtx = nco.getNodeOutput("start");
        Object triggerOutput = startCtx != null ? startCtx.getOutput() : null;
        input.put("trigger", triggerOutput);

        // Named outputs container (input.nex.sub, input.nex.userData, etc.) — same as top-level nex
        input.put("nex", nco.getNex() != null ? nco.getNex() : new LinkedHashMap<>());

        return input;
    }

    /**
     * Returns true if the script output should be treated as failure: null, or object { result: null }.
     */
    @SuppressWarnings("unchecked")
    private boolean isNullOrEmptyResult(Object output) {
        if (output == null) return true;
        if (output instanceof Map<?, ?> map) {
            if (map.containsKey("result") && map.size() == 1) {
                return ((Map<String, Object>) map).get("result") == null;
            }
        }
        return false;
    }

    private NodeContext failure(String nodeId, String error) {
        return NodeContext.builder()
                .nodeId(nodeId)
                .nodeType(NodeType.SCRIPT.name())
                .status(NodeStatus.FAILURE)
                .input(Map.of())
                .failureOutput(Map.of("error", error))
                .errorMessage(error)
                .build();
    }
}
