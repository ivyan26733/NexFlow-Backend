package com.nexflow.nexflow_backend.executor.impl;

import com.nexflow.nexflow_backend.engine.ScriptRunner;
import com.nexflow.nexflow_backend.repository.NodeExecutor;
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
            return failure(nodeId, "This script step has no code yet. Open it and write the script.");
        }
        int timeoutSeconds = node.configValue("timeoutSeconds", 10) instanceof Number n ? n.intValue() : 10;

        ScriptRunner.ScriptResult result = scriptRunner.run(language, code, nco.toScriptData(), timeoutSeconds);
        if (!result.success()) return failure(nodeId, result.error());

        boolean nullOutput = isNullOrEmptyResult(result.output());
        // Opt-in: "failOnNullOutput": true treats a missing return value as an error.
        if (nullOutput && Boolean.TRUE.equals(config.get("failOnNullOutput"))) {
            return failure(nodeId, "The script didn't return anything. A value it depends on is probably missing — "
                    + "check that the nex names it uses exist.");
        }

        Map<String, Object> successOutput = new LinkedHashMap<>();
        successOutput.put("result", nullOutput ? null : result.output());
        successOutput.put("language", language);
        return NodeContext.builder()
                .nodeId(nodeId)
                .nodeType(NodeType.SCRIPT.name())
                .status(NodeStatus.SUCCESS)
                .input(Map.of("language", language, "codeLength", code.length()))
                .successOutput(successOutput)
                .build();
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

    private static NodeContext failure(String nodeId, String error) {
        return NodeContext.builder()
                .nodeId(nodeId)
                .nodeType(NodeType.SCRIPT.name())
                .status(NodeStatus.FAILURE)
                .failureOutput(Map.of("error", error))
                .errorMessage(error)
                .build();
    }
}
