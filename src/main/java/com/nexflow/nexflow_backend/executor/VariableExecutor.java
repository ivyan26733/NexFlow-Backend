package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class VariableExecutor implements NodeExecutor {

    private final ReferenceResolver resolver;

    @Override
    public NodeType supportedType() {
        return NodeType.VARIABLE;
    }

    /*
     * Config shape:
     * {
     *   "variables": {
     *     "userId": "static-value",
     *     "userPlan": "{{nodes.pulse_001.successOutput.body.plan}}"
     *   }
     * }
     */
    @Override
    @SuppressWarnings("unchecked")
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> variableDefs = (Map<String, Object>) node.getConfig().getOrDefault("variables", new HashMap<>());

        Map<String, Object> resolved = resolver.resolveMap(variableDefs, nco);
        resolved.forEach(nco::setVariable);

        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(NodeType.VARIABLE.name())
                .status(NodeStatus.SUCCESS)
                .output(resolved)
                .build();
    }
}
