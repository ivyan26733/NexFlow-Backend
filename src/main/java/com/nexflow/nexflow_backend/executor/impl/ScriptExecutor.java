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
 *   "trigger": { ...the original trigger payload from the START node... }
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
            Map<String, Object> successOutput = new LinkedHashMap<>();
            successOutput.put("result",   result.output());
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

        return input;
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
