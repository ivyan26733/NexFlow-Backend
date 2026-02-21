package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import org.springframework.stereotype.Component;

@Component
public class StartExecutor implements NodeExecutor {

    @Override
    public NodeType supportedType() {
        return NodeType.START;
    }

    // START node just marks itself as success; the Pulse payload is pre-loaded into NCO by the engine
    @Override
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(NodeType.START.name())
                .status(NodeStatus.SUCCESS)
                .build();
    }
}
