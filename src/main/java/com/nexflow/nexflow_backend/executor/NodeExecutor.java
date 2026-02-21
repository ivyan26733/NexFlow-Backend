package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;

public interface NodeExecutor {

    NodeType supportedType();

    // Executes the node logic, reads from NCO, writes result back into it
    NodeContext execute(FlowNode node, NexflowContextObject nco);
}
