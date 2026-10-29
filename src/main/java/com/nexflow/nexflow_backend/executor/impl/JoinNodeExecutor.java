package com.nexflow.nexflow_backend.executor.impl;

import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.repository.NodeExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * JOIN marks where parallel branches meet. The waiting and merging is done by the FORK
 * (ForkNodeExecutor), so by the time a JOIN runs nex already holds the merged results and nex.join.
 */
@Component
public class JoinNodeExecutor implements NodeExecutor {

    @Override
    public NodeType supportedType() {
        return NodeType.JOIN;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Object joinMeta = nco.getNex().get("join");
        if (!(joinMeta instanceof Map<?, ?>)) {
            String message = "The Join '" + node.displayName() + "' was reached without a Fork before it, "
                    + "so there are no branch results to merge. Connect it after the steps of a Fork's branches.";
            return NodeContext.builder()
                    .nodeId(node.getId().toString())
                    .nodeType(NodeType.JOIN.name())
                    .status(NodeStatus.FAILURE)
                    .errorMessage(message)
                    .errorDetail(message)
                    .build();
        }
        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(NodeType.JOIN.name())
                .status(NodeStatus.SUCCESS)
                .output((Map<String, Object>) joinMeta)
                .build();
    }
}
