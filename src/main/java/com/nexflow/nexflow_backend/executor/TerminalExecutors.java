package com.nexflow.nexflow_backend.executor;

import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.repository.NodeExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
abstract class TerminalExecutor implements NodeExecutor {

    protected final ReferenceResolver resolver;

    /*
     * Config shape:
     * {
     *   "response": {
     *     "message": "Flow completed",
     *     "userId":  "{{variables.userId}}"
     *   }
     * }
     */
    @Override
    @SuppressWarnings("unchecked")
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> responseTemplate = (Map<String, Object>) node.configValue("response", new HashMap<>());
        Map<String, Object> resolvedResponse = resolver.resolveMap(responseTemplate, nco);

        NodeContext.NodeContextBuilder result = NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(node.getNodeType().name())
                .status(terminalStatus())
                .output(resolvedResponse);
        if (terminalStatus() == NodeStatus.FAILURE) {
            String message = "The flow reached its failure end step '" + node.displayName() + "'.";
            result.errorMessage(message).errorDetail(message);
        }
        return result.build();
    }

    protected abstract NodeStatus terminalStatus();
}

@Component
class SuccessExecutor extends TerminalExecutor {
    SuccessExecutor(ReferenceResolver resolver) { super(resolver); }

    @Override public NodeType supportedType() { return NodeType.SUCCESS; }
    @Override protected NodeStatus terminalStatus() { return NodeStatus.SUCCESS; }
}

@Component
class FailureExecutor extends TerminalExecutor {
    FailureExecutor(ReferenceResolver resolver) { super(resolver); }

    @Override public NodeType supportedType() { return NodeType.FAILURE; }
    @Override protected NodeStatus terminalStatus() { return NodeStatus.FAILURE; }
}
