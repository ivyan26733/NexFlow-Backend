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
import com.nexflow.nexflow_backend.model.nco.RetryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.repository.FlowEdgeRepository;
import com.nexflow.nexflow_backend.repository.FlowNodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class FlowExecutionEngine {

    private final FlowNodeRepository       nodeRepository;
    private final FlowEdgeRepository       edgeRepository;
    private final NodeExecutorRegistry     executorRegistry;
    private final ExecutionEventPublisher  eventPublisher;
    private final ObjectMapper             objectMapper;

    public FlowExecutionEngine(FlowNodeRepository nodeRepository,
                               FlowEdgeRepository edgeRepository,
                               NodeExecutorRegistry executorRegistry,
                               ExecutionEventPublisher eventPublisher,
                               ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.executorRegistry = executorRegistry;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    private static final UUID DEFAULT_START_NODE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Hard cap on node executions per run to avoid infinite loops; does not kill the process, just exits cleanly. */
    private static final int MAX_NODE_EXECUTIONS = 5_000;

    public NexflowContextObject execute(UUID flowId, String executionId, Map<String, Object> triggerPayload) {
        NexflowContextObject nco = NexflowContextObject.create(flowId.toString(), executionId);

        List<FlowNode> allNodes = new ArrayList<>(nodeRepository.findByFlowId(flowId));
        List<FlowEdge> allEdges = edgeRepository.findByFlowId(flowId);

        FlowNode startNode = findStartNodeOrCreateDefault(allNodes, flowId);
        injectTriggerPayload(startNode, nco, triggerPayload);

        Map<UUID, FlowNode> nodeMap = new HashMap<>();
        allNodes.forEach(n -> nodeMap.put(n.getId(), n));
        for (FlowNode n : allNodes) {
            if (n.getNodeType() == NodeType.LOOP) {
                boolean hasContinue = allEdges.stream()
                    .anyMatch(e -> e.getSourceNodeId().equals(n.getId()) && e.getConditionType() == EdgeCondition.CONTINUE);
                if (nco.getMeta().getLoopNodeHasContinueEdge() == null) {
                    nco.getMeta().setLoopNodeHasContinueEdge(new HashMap<>());
                }
                nco.getMeta().getLoopNodeHasContinueEdge().put(n.getId().toString(), hasContinue);
            }
        }

        Queue<FlowNode> queue = new LinkedList<>();
        queue.add(startNode);
        boolean CheckOutputFlag = true;
        Set<UUID> executedNodeIds = new HashSet<>();

        while (!queue.isEmpty()) {
            FlowNode current = queue.poll();
            nco.getMeta().setCurrentNodeId(current.getId().toString());

            // Safety: max steps to avoid runaway execution (no process kill, clean exit)
            if (nco.getNodeExecutionOrder().size() >= MAX_NODE_EXECUTIONS) {
                log.warn("Execution {} stopped: max steps ({}) exceeded. Possible loop in flow.", executionId, MAX_NODE_EXECUTIONS);
                nco.getMeta().setErrorMessage(
                    "Execution stopped: max steps exceeded (" + MAX_NODE_EXECUTIONS + "). Possible loop in flow (e.g. SubFlow ↔ Script). Fix the flow to remove cycles."
                );
                CheckOutputFlag = false;
                break;
            }

            eventPublisher.nodeStarted(executionId, current.getId().toString());
            NodeContext result = runNode(current, nco, executionId);
            // Do not overwrite START node output (set in injectTriggerPayload with output.body)
            if (!current.getId().equals(startNode.getId())) {
                nco.setNodeOutput(current.getId().toString(), result);
                nco.setNodeAlias(toLabelKey(current.getLabel()), result);
                // Check if this node wants to save output into nex (skip VARIABLE/LOOP — they handle nex themselves where needed)
                if (current.getNodeType() != NodeType.VARIABLE && current.getNodeType() != NodeType.LOOP) {
                    String saveAs = extractSaveOutputAs(current);
                    if (saveAs != null && !saveAs.isBlank()) {
                        String key = saveAs.trim();
                        if (key.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                            Object valueForNex = result.getSuccessOutput() != null ? result.getSuccessOutput() : result.getOutput();
                            if (valueForNex != null) {
                                if (nco.getNex().containsKey(key)) {
                                    log.warn("saveOutputAs '{}' on node '{}' overwrites existing nex entry from an earlier node.", key, current.getLabel());
                                }
                                nco.getNex().put(key, valueForNex);
                            }
                        } else {
                            log.warn("saveOutputAs value '{}' on node '{}' is not a valid key. Only letters, numbers, underscore allowed. Skipping.", key, current.getLabel());
                        }
                    }
                }
            }
            eventPublisher.nodeCompleted(executionId, current.getId().toString(), result.getStatus(), nco.getNex());
            nco.getNodeExecutionOrder().add(current.getId().toString());
            executedNodeIds.add(current.getId());

            if(result.getStatus() == NodeStatus.FAILURE) CheckOutputFlag = false;

            if(isTerminal(current.getNodeType())) break;

            // Resolve next nodes, then filter: allow re-entry only for LOOP nodes (intentional); others = cycle → FAILURE
            List<FlowNode> nextNodes = new ArrayList<>(resolveNextNodes(current, result.getStatus(), allEdges, allNodes));
            nextNodes.sort(Comparator.comparing((FlowNode n) -> isTerminal(n.getNodeType()) ? 1 : 0));

            List<FlowNode> toEnqueue = new ArrayList<>();
            for (FlowNode next : nextNodes) {
                if (!executedNodeIds.contains(next.getId())) {
                    toEnqueue.add(next);
                } else {
                    // Re-entry: allow when following LOOP's CONTINUE edge (loop back to body), or when next is a LOOP node
                    boolean allowReEntry = result.getStatus() == NodeStatus.CONTINUE
                        || (nodeMap.get(next.getId()) != null && nodeMap.get(next.getId()).getNodeType() == NodeType.LOOP);
                    if (allowReEntry) {
                        toEnqueue.add(next);
                    }
                }
            }

            if (!nextNodes.isEmpty() && toEnqueue.isEmpty()) {
                // All next nodes were already executed and re-entry not allowed (no CONTINUE edge, no LOOP) → accidental cycle
                log.warn("Execution {} stopped: loop detected. Flow would re-enter node(s) that already ran.", executionId);
                nco.getMeta().setErrorMessage(
                    "Loop detected: execution would re-enter a node that already ran. Check for cycles in the flow (e.g. SubFlow connected back to Script, or two nodes pointing to each other). Remove the cycle to fix."
                );
                CheckOutputFlag = false;
                break;
            }
            queue.addAll(toEnqueue);
        }

        finalizeExecution(nco, CheckOutputFlag);
        return nco;
    }
    
    private NodeContext runNode(FlowNode flowNode, NexflowContextObject nco, String executionId) {
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

        RetryConfig retry = extractRetryConfig(flowNode);
        int maxRetries = Math.max(0, Math.min(10, retry.getMaxRetries()));
        long delayMs = retry.getBackoffMs() > 0 ? retry.getBackoffMs() : 1000L;
        double multiplier = retry.getBackoffMultiplier() > 0 ? retry.getBackoffMultiplier() : 1.0d;

        int attempt = 0;
        NodeContext lastContext = null;

        while (true) {
            attempt++;
            try {
                lastContext = executorRegistry.get(nodeType).execute(flowNode, nco);
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                log.error("Node {} ({}) threw on attempt {}: {}", flowNode.getId(), nodeType, attempt, msg, ex);
                lastContext = NodeContext.builder()
                        .nodeId(flowNode.getId().toString())
                        .nodeType(nodeType.name())
                        .status(NodeStatus.FAILURE)
                        .errorMessage(msg)
                        .build();
            }

            if (lastContext.getStatus() != NodeStatus.FAILURE) {
                return lastContext;
            }

            if (attempt > maxRetries) {
                // All retries exhausted — return last FAILURE context
                return lastContext;
            }

            long waitMs = delayMs;
            log.warn("Node {} ({}) failed on attempt {}/{}. Retrying in {} ms",
                    flowNode.getId(), nodeType, attempt, maxRetries + 1, waitMs);
            eventPublisher.nodeRetrying(executionId, flowNode.getId().toString());

            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Retry sleep interrupted for node {} — aborting further retries", flowNode.getId());
                return lastContext;
            }

            // Exponential backoff for next attempt
            delayMs = (long) Math.max(0L, delayMs * multiplier);
        }
    }

    @SuppressWarnings("unchecked")
    private RetryConfig extractRetryConfig(FlowNode flowNode) {
        Map<String, Object> cfg = flowNode.getConfig();
        if (cfg == null || !cfg.containsKey("retry")) {
            return new RetryConfig();
        }
        Object raw = cfg.get("retry");
        try {
            RetryConfig retry;
            if (raw instanceof Map<?, ?> map) {
                retry = objectMapper.convertValue(map, RetryConfig.class);
            } else {
                retry = objectMapper.convertValue(raw, RetryConfig.class);
            }
            // Defensive bounds so bad config cannot explode the engine
            int maxRetries = Math.max(0, Math.min(10, retry.getMaxRetries()));
            retry.setMaxRetries(maxRetries);
            if (retry.getBackoffMs() <= 0L) {
                retry.setBackoffMs(1000L);
            }
            if (retry.getBackoffMultiplier() <= 0d) {
                retry.setBackoffMultiplier(1.0d);
            }
            return retry;
        } catch (IllegalArgumentException ex) {
            log.warn("Failed to parse retry config for node {}: {}", flowNode.getId(), ex.getMessage());
            return new RetryConfig();
        }
    }

    // Finds nodes reachable from current node based on the node's outcome
    private List<FlowNode> resolveNextNodes(FlowNode current, NodeStatus outcome,
                                            List<FlowEdge> allEdges, List<FlowNode> allNodes) {
        EdgeCondition requiredCondition;
        if (outcome == NodeStatus.SUCCESS) {
            requiredCondition = EdgeCondition.SUCCESS;
        } else if (outcome == NodeStatus.FAILURE) {
            requiredCondition = EdgeCondition.FAILURE;
        } else if (outcome == NodeStatus.CONTINUE) {
            requiredCondition = EdgeCondition.CONTINUE;
        } else {
            requiredCondition = EdgeCondition.DEFAULT;
        }

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
        // If START node has "Save output as", put trigger payload in nex so input.nex.start works in child flows
        String saveAs = extractSaveOutputAs(startNode);
        if (saveAs != null && !saveAs.isBlank()) {
            String key = saveAs.trim();
            if (key.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                nco.getNex().put(key, output);
            }
        }
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

    private FlowNode findStartNodeOrCreateDefault(List<FlowNode> nodes, UUID flowId) {
        return nodes.stream()
                .filter(n -> n.getNodeType() == NodeType.START)
                .findFirst()
                .orElseGet(() -> {
                    FlowNode defaultStart = new FlowNode();
                    defaultStart.setId(DEFAULT_START_NODE_ID);
                    defaultStart.setFlowId(flowId);
                    defaultStart.setNodeType(NodeType.START);
                    defaultStart.setLabel("Start");
                    defaultStart.setConfig(Map.of());
                    defaultStart.setPositionX(0.0);
                    defaultStart.setPositionY(0.0);
                    nodes.add(defaultStart);
                    return defaultStart;
                });
    }

    private String extractSaveOutputAs(FlowNode node) {
        try {
            Map<String, Object> config = node.getConfig();
            if (config == null) return null;
            Object val = config.get("saveOutputAs");
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isTerminal(NodeType type) {
        return type == NodeType.SUCCESS || type == NodeType.FAILURE;
    }

    private void finalizeExecution(NexflowContextObject nco, boolean result) {
        nco.getMeta().setCompletedAt(Instant.now());
        if (!result) {
            nco.getMeta().setStatus(ExecutionStatus.FAILURE);
        } else {
            nco.getMeta().setStatus(ExecutionStatus.SUCCESS);
        }
    }
}