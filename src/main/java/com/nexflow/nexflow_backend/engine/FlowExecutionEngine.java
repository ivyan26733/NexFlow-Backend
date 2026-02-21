package com.nexflow.nexflow_backend.engine;

import com.nexflow.nexflow_backend.EdgeCondition;
import com.nexflow.nexflow_backend.executor.NodeExecutorRegistry;
import com.nexflow.nexflow_backend.model.domain.FlowEdge;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.repository.FlowEdgeRepository;
import com.nexflow.nexflow_backend.repository.FlowNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlowExecutionEngine {

    private final FlowNodeRepository nodeRepository;
    private final FlowEdgeRepository edgeRepository;
    private final NodeExecutorRegistry executorRegistry;
    private final ExecutionEventPublisher eventPublisher;

    public NexflowContextObject execute(UUID flowId, String executionId, Map<String, Object> triggerPayload) {
        NexflowContextObject nco = NexflowContextObject.create(flowId.toString(), executionId);

        List<FlowNode> allNodes = nodeRepository.findByFlowId(flowId);
        List<FlowEdge> allEdges = edgeRepository.findByFlowId(flowId);

        FlowNode startNode = findStartNode(allNodes);
        injectTriggerPayload(startNode, nco, triggerPayload);

        Queue<FlowNode> queue = new LinkedList<>();
        queue.add(startNode);

        while (!queue.isEmpty()) {
            FlowNode current = queue.poll();
            nco.getMeta().setCurrentNodeId(current.getId().toString());

            eventPublisher.nodeStarted(executionId, current.getId().toString());
            NodeContext result = runNode(current, nco);
            nco.setNodeOutput(current.getId().toString(), result);
            eventPublisher.nodeCompleted(executionId, current.getId().toString(), result.getStatus());

            if (isTerminal(current.getNodeType())) break;

            List<FlowNode> nextNodes = resolveNextNodes(current, result.getStatus(), allEdges, allNodes);
            queue.addAll(nextNodes);
        }

        finalizeExecution(nco);
        return nco;
    }

    private NodeContext runNode(FlowNode node, NexflowContextObject nco) {
        try {
            return executorRegistry.get(node.getNodeType()).execute(node, nco);
        } catch (Exception ex) {
            log.error("Node {} execution failed: {}", node.getId(), ex.getMessage());
            return NodeContext.builder()
                    .nodeId(node.getId().toString())
                    .nodeType(node.getNodeType().name())
                    .status(NodeStatus.FAILURE)
                    .errorMessage(ex.getMessage())
                    .build();
        }
    }

    // Finds nodes reachable from current node based on the node's outcome
    private List<FlowNode> resolveNextNodes(FlowNode current, NodeStatus outcome,
                                            List<FlowEdge> allEdges, List<FlowNode> allNodes) {
        EdgeCondition requiredCondition = outcome == NodeStatus.SUCCESS
                ? EdgeCondition.SUCCESS
                : EdgeCondition.FAILURE;

        Map<UUID, FlowNode> nodeMap = new HashMap<>();
        allNodes.forEach(n -> nodeMap.put(n.getId(), n));

        return allEdges.stream()
                .filter(e -> e.getSourceNodeId().equals(current.getId()))
                .filter(e -> e.getConditionType() == requiredCondition || e.getConditionType() == EdgeCondition.DEFAULT)
                .map(e -> nodeMap.get(e.getTargetNodeId()))
                .filter(Objects::nonNull)
                .toList();
    }

    private void injectTriggerPayload(FlowNode startNode, NexflowContextObject nco, Map<String, Object> payload) {
        NodeContext startCtx = NodeContext.builder()
                .nodeId(startNode.getId().toString())
                .nodeType(NodeType.START.name())
                .status(NodeStatus.SUCCESS)
                .output(payload)
                .build();
        nco.setNodeOutput(startNode.getId().toString(), startCtx);
    }

    private FlowNode findStartNode(List<FlowNode> nodes) {
        return nodes.stream()
                .filter(n -> n.getNodeType() == NodeType.START)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Flow has no START node"));
    }

    private boolean isTerminal(NodeType type) {
        return type == NodeType.SUCCESS || type == NodeType.FAILURE;
    }

    private void finalizeExecution(NexflowContextObject nco) {
        nco.getMeta().setCompletedAt(Instant.now());
        nco.getMeta().setStatus(ExecutionStatus.SUCCESS);
    }
}
