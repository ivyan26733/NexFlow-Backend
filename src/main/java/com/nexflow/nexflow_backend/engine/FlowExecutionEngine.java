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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;

@Slf4j
@Service
public class FlowExecutionEngine {

    private final FlowNodeRepository nodeRepository;
    private final FlowEdgeRepository edgeRepository;
    private final NodeExecutorRegistry executorRegistry;
    private final ExecutionEventPublisher eventPublisher;

    public FlowExecutionEngine(FlowNodeRepository nodeRepository, FlowEdgeRepository edgeRepository,
                               NodeExecutorRegistry executorRegistry, ExecutionEventPublisher eventPublisher) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.executorRegistry = executorRegistry;
        this.eventPublisher = eventPublisher;
    }

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
            // Do not overwrite START node output (set in injectTriggerPayload with output.body)
            if (!current.getId().equals(startNode.getId())) {
                nco.setNodeOutput(current.getId().toString(), result);
                nco.setNodeAlias(toLabelKey(current.getLabel()), result);
            }
            eventPublisher.nodeCompleted(executionId, current.getId().toString(), result.getStatus());
            nco.getNodeExecutionOrder().add(current.getId().toString());

            if (isTerminal(current.getNodeType())) break;

            // Copy to mutable list (resolveNextNodes returns immutable toList()); sort so non-terminals run before SUCCESS/FAILURE
            List<FlowNode> nextNodes = new ArrayList<>(resolveNextNodes(current, result.getStatus(), allEdges, allNodes));
            nextNodes.sort(Comparator.comparing((FlowNode n) -> isTerminal(n.getNodeType()) ? 1 : 0));
            queue.addAll(nextNodes);
        }

        finalizeExecution(nco);
        return nco;
    }
    
    private NodeContext runNode(FlowNode flowNode, NexflowContextObject nco) {
        NodeType nodeType = flowNode.getNodeType();
        if (nodeType == null) {
            log.error("Node {} has null nodeType", flowNode.getId());
            return NodeContext.builder()
                    .nodeId(flowNode.getId().toString())
                    .nodeType("UNKNOWN")
                    .status(NodeStatus.FAILURE)
                    .errorMessage("Node type is null")
                    .build();
        }
        try {
            return executorRegistry.get(nodeType).execute(flowNode, nco);
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.error("Node {} ({}): {}", flowNode.getId(), nodeType, msg, ex);
            return NodeContext.builder()
                    .nodeId(flowNode.getId().toString())
                    .nodeType(nodeType.name())
                    .status(NodeStatus.FAILURE)
                    .errorMessage(msg)
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
        // Defensive copy so we own the map and it is never null
        Map<String, Object> body = payload != null && !payload.isEmpty()
                ? new HashMap<>(payload)
                : new HashMap<>();

        // Store under output.body so {{nodes.start.output.body.a}} resolves correctly.
        // This is the only place START node output is set; the main loop must not overwrite it.
        Map<String, Object> output = new HashMap<>();
        output.put("body", body);

        NodeContext startCtx = NodeContext.builder()
                .nodeId(startNode.getId().toString())
                .nodeType(NodeType.START.name())
                .status(NodeStatus.SUCCESS)
                .output(output)
                .build();
        nco.setNodeOutput(startNode.getId().toString(), startCtx);
        nco.setNodeAlias("start", startCtx);
    }

    /**
     * Converts a node label to a camelCase key for {{}} refs (e.g. "Calculate Discount" → "calculateDiscount").
     */
    public static String toLabelKey(String label) {
        if (label == null || label.isBlank()) return "node";
        String[] words = label.trim().replaceAll("[^a-zA-Z0-9 ]", "").split("\\s+");
        if (words.length == 0 || words[0].isBlank()) return "node";
        StringBuilder key = new StringBuilder(words[0].toLowerCase());
        for (int i = 1; i < words.length; i++) {
            if (!words[i].isBlank()) {
                key.append(Character.toUpperCase(words[i].charAt(0)));
                key.append(words[i].substring(1).toLowerCase());
            }
        }
        return key.toString();
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