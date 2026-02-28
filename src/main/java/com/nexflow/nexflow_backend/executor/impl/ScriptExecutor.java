package com.nexflow.nexflow_backend.executor.impl;

import com.nexflow.nexflow_backend.engine.ScriptRunner;
import com.nexflow.nexflow_backend.executor.NodeExecutor;
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
 *   "code":     "const filtered = input.nodes.pulse1.successOutput.body.items..."
 * }
 *
 * What the script receives as `input`:
 * {
 *   "variables": { ...all flow variables... },
 *   "nodes": { ...all previous node outputs, keyed by nodeId... },
 *   "trigger": { ...the original trigger payload from the START node... },
 *   "nex": { ...named outputs from nodes with "Save output as" (e.g. input.nex.sub, input.nex.userData)... }
 * }
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
            return failure(nodeId, "SCRIPT node has no code. Open the node and write your script.");
        }

        // Build the input object the user's script will receive
        Map<String, Object> scriptInput = buildInput(nco);

        // Run it
        ScriptRunner.ScriptResult result = scriptRunner.run(language, code, scriptInput);

        if (result.success()) {
            Object output = result.output();
            // Treat null or { result: null } as failure so the flow can route to the failure edge
            if (isNullOrEmptyResult(output)) {
                return failure(nodeId,
                    "Script returned null or no value. Return a non-null value for success, or throw an error to fail.");
            }
            Map<String, Object> successOutput = new LinkedHashMap<>();
            successOutput.put("result",   output);
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
     * Builds the `input` object injected into the user's script.
     * Gives access to variables, all node outputs, and the original trigger payload.
     */
    private Map<String, Object> buildInput(NexflowContextObject nco) {
        Map<String, Object> input = new LinkedHashMap<>();

        // All flow variables set by VARIABLE nodes
        input.put("variables", nco.getVariables());

        // All previous node outputs (UUID + label keys) so input.nodes.calculateDiscount works
        input.put("nodes", nco.getNodesForScriptInput());

        // Shortcut to the original trigger payload (START node output)
        NodeContext startCtx = nco.getNodeOutput("start");
        Object triggerOutput = startCtx != null ? startCtx.getOutput() : null;
        input.put("trigger", triggerOutput);

        // Named outputs from nodes with "Save output as" (input.nex.sub, input.nex.userData, etc.)
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
