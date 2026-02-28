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
public class MapperExecutor implements NodeExecutor {

    private final ReferenceResolver resolver;

    @Override
    public NodeType supportedType() {
        return NodeType.MAPPER;
    }

    /*
     * Config shape:
     * {
     *   "output": {
     *     "email":  "{{variables.email}}",
     *     "amount": "{{nodes.start_001.output.body.amount}}",
     *     "plan":   "premium"
     *   }
     * }
     *
     * The resolved output becomes the input for the next node (e.g. NEXUS).
     */

    @Override
    @SuppressWarnings("unchecked")
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> outputTemplate = (Map<String, Object>) node.getConfig().getOrDefault("output", new HashMap<>());
        Map<String, Object> resolvedOutput = resolver.resolveMap(outputTemplate, nco);

        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(NodeType.MAPPER.name())
                .status(NodeStatus.SUCCESS)
                .output(resolvedOutput)
                .build();
    }
}
