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
import com.nexflow.nexflow_backend.model.domain.NodeExecution;
import com.nexflow.nexflow_backend.model.domain.NodeExecutionStatus;
import com.nexflow.nexflow_backend.repository.FlowEdgeRepository;
import com.nexflow.nexflow_backend.repository.FlowNodeRepository;
import com.nexflow.nexflow_backend.service.NodeExecutionPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FlowExecutionEngine {

    private final FlowNodeRepository       nodeRepository;
    private final FlowEdgeRepository       edgeRepository;
    private final NodeExecutorRegistry     executorRegistry;
    private final ExecutionEventPublisher  eventPublisher;
    private final ObjectMapper             objectMapper;
    private final NodeExecutionPersistenceService nodeExecutionPersistence;

    public FlowExecutionEngine(FlowNodeRepository nodeRepository,
                               FlowEdgeRepository edgeRepository,
                               NodeExecutorRegistry executorRegistry,
                               ExecutionEventPublisher eventPublisher,
                               ObjectMapper objectMapper,
                               NodeExecutionPersistenceService nodeExecutionPersistence) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.executorRegistry = executorRegistry;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.nodeExecutionPersistence = nodeExecutionPersistence;
    }

    private static final UUID DEFAULT_START_NODE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Hard cap on node executions per run to avoid infinite loops; does not kill the process, just exits cleanly. */
    private static final int MAX_NODE_EXECUTIONS = 5_000;

    public NexflowContextObject execute(UUID flowId, String executionId, Map<String, Object> triggerPayload) {
        NexflowContextObject nco = NexflowContextObject.create(flowId.toString(), executionId);

        log.info(
                "[FlowExecutionEngine] START executionId={} flowId={} triggerKeys={}",
                executionId,
                flowId,
                triggerPayload != null ? triggerPayload.keySet() : "null"
        );

        List<FlowNode> allNodes = new ArrayList<>(nodeRepository.findByFlowId(flowId));
        List<FlowEdge> allEdges = edgeRepository.findByFlowId(flowId);

        // If a FORK node has no branchNodeIds in config (e.g. flow saved before UI set it), derive from edges:
        // edges from FORK with sourceHandle = branch name → target node belongs to that branch.
        ensureForkBranchNodeIdsFromEdges(allNodes, allEdges);

        // Make all flow nodes available on the NCO so branch executors can resolve
        // nodes by ID without hitting the database from branch threads.
        nco.setFlowNodes(allNodes);

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
        boolean checkOutputFlag = true;
        boolean reachedSuccessTerminal = false;
        Set<UUID> executedNodeIds = new HashSet<>();
        /** Nodes we marked executed only because they ran inside a FORK branch (so we don't treat "JOIN → branch nodes" as a loop). */
        Set<UUID> branchOnlyExecutedNodeIds = new HashSet<>();

        while (!queue.isEmpty()) {
            FlowNode current = queue.poll();
            // The engine works one node at a time, then chooses what should run next.
            nco.getMeta().setCurrentNodeId(current.getId().toString());

            log.debug(
                    "[FlowExecutionEngine] Node START executionId={} nodeId={} label={} type={}",
                    executionId,
                    current.getId(),
                    current.getLabel(),
                    current.getNodeType()
            );

            // Safety: max steps to avoid runaway execution (no process kill, clean exit)
            if (nco.getNodeExecutionOrder().size() >= MAX_NODE_EXECUTIONS) {
                log.warn("Execution {} stopped: max steps ({}) exceeded. Possible loop in flow.", executionId, MAX_NODE_EXECUTIONS);
                nco.getMeta().setErrorMessage(
                    "Execution stopped: max steps exceeded (" + MAX_NODE_EXECUTIONS + "). Possible loop in flow (e.g. SubFlow ↔ Script). Fix the flow to remove cycles."
                );
                checkOutputFlag = false;
                break;
            }

            eventPublisher.nodeStarted(executionId, current.getId().toString());
            NodeContext result;
            try {
                result = runNode(current, nco, executionId);
                // Do not overwrite START node output (set in injectTriggerPayload with output.body)
                if (!current.getId().equals(startNode.getId())) {
                    nco.setNodeOutput(current.getId().toString(), result);
                    nco.setNodeAlias(toLabelKey(current.getLabel()), result);
                    // Auto-populate nex for every non-VARIABLE, non-LOOP node so downstream nodes
                    // can access output via {{nex.nodeLabelCamelCase.field}} or nex.nodeLabelCamelCase in scripts.
                    // VARIABLE nodes populate nex themselves (flat variable spread).
                    if (current.getNodeType() != NodeType.VARIABLE && current.getNodeType() != NodeType.LOOP) {
                        Object valueForNex = result.getSuccessOutput() != null ? result.getSuccessOutput() : result.getOutput();
                        if (valueForNex != null) {
                            // 1. Auto-add under camelCase label (putIfAbsent — explicit saveOutputAs key takes priority)
                            String labelKey = toLabelKey(current.getLabel());
                            nco.getNex().putIfAbsent(labelKey, valueForNex);

                            // 2. Explicit "Save output as" — always overwrites so the user's chosen name wins
                            String saveAs = extractSaveOutputAs(current);
                            if (saveAs != null && !saveAs.isBlank()) {
                                String key = saveAs.trim();
                                if (key.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                                    nco.getNex().put(key, valueForNex);
                                } else {
                                    log.warn("saveOutputAs value '{}' on node '{}' is not a valid key. Only letters, numbers, underscore allowed. Skipping.", key, current.getLabel());
                                }
                            }
                        }
                    }
                }
                eventPublisher.nodeCompleted(executionId, current.getId().toString(), result.getStatus(), nco.getNex());
            } catch (Throwable t) {
                String errMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                log.error("Node {} did not complete normally: {}", current.getId(), errMsg, t);
                eventPublisher.nodeError(executionId, current.getId().toString(), errMsg);
                result = NodeContext.builder()
                        .nodeId(current.getId().toString())
                        .nodeType(current.getNodeType().name())
                        .status(NodeStatus.FAILURE)
                        .errorMessage(errMsg)
                        .build();
                nco.setNodeOutput(current.getId().toString(), result);
            }

            log.debug(
                    "[FlowExecutionEngine] Node END executionId={} nodeId={} status={} nexKeys={}",
                    executionId,
                    current.getId(),
                    result.getStatus(),
                    nco.getNex() != null ? nco.getNex().keySet() : "null"
            );

            nco.getNodeExecutionOrder().add(current.getId().toString());
            executedNodeIds.add(current.getId());

            // Nodes that ran inside FORK branches were not "current" in the main loop, so they are not in executedNodeIds.
            // Mark them as executed so we never run them again after the JOIN (avoids re-executing branch scripts).
            if (current.getNodeType() == NodeType.FORK && result.getStatus() == NodeStatus.SUCCESS) {
                addForkBranchNodeIdsToExecuted(current, executedNodeIds, branchOnlyExecutedNodeIds);
            }

            if (result.getStatus() == NodeStatus.FAILURE) checkOutputFlag = false;
            if (current.getNodeType() == NodeType.SUCCESS && result.getStatus() == NodeStatus.SUCCESS) reachedSuccessTerminal = true;

            // LOOP is the only node type that is allowed to point back to itself on purpose.
            // Any other cycle is treated as a broken flow.
            // Hard fail-fast for FORK nodes that themselves failed (e.g. onBranchFailure=FAIL_FAST or WAIT_N quorum not met):
            // as soon as the FORK returns FAILURE, stop enqueuing any further nodes.
            if (current.getNodeType() == NodeType.FORK && result.getStatus() == NodeStatus.FAILURE) {
                log.error("[FlowExecutionEngine] Node '{}' (FORK) returned FAILURE — stopping flow execution (JOIN and downstream nodes will NOT run)",
                        current.getLabel());
                break;
            }

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
                // All next nodes were already executed. If they are only FORK branch nodes we already ran, follow edges FROM them (e.g. to JOIN) and enqueue those.
                boolean allNextAreBranchNodesOnly = nextNodes.stream()
                        .allMatch(next -> branchOnlyExecutedNodeIds.contains(next.getId()));
                if (allNextAreBranchNodesOnly) {
                    // BFS through already-executed branch-only nodes to find the first non-executed successor (e.g. JOIN).
                    // One-hop is insufficient for multi-node branches where entry node → intermediate branch nodes → JOIN.
                    Set<UUID> visitedForward = new HashSet<>();
                    Queue<FlowNode> forwardQueue = new LinkedList<>(nextNodes);
                    while (!forwardQueue.isEmpty()) {
                        FlowNode branchNode = forwardQueue.poll();
                        if (!visitedForward.add(branchNode.getId())) continue;
                        List<FlowNode> afterBranch = resolveNextNodes(branchNode, NodeStatus.SUCCESS, allEdges, allNodes);
                        for (FlowNode n : afterBranch) {
                            if (!executedNodeIds.contains(n.getId()) && toEnqueue.stream().noneMatch(x -> x.getId().equals(n.getId()))) {
                                toEnqueue.add(n);
                            } else if (branchOnlyExecutedNodeIds.contains(n.getId()) && !visitedForward.contains(n.getId())) {
                                // This is another branch node; keep traversing toward JOIN
                                forwardQueue.add(n);
                            }
                        }
                    }
                    if (toEnqueue.isEmpty()) {
                        log.debug("[FlowExecutionEngine] No nodes after branch nodes (e.g. no JOIN or downstream); completing normally.");
                    }
                } else {
                    log.warn("Execution {} stopped: loop detected. Flow would re-enter node(s) that already ran.", executionId);
                    nco.getMeta().setErrorMessage(
                        "Loop detected: execution would re-enter a node that already ran. Check for cycles in the flow (e.g. SubFlow connected back to Script, or two nodes pointing to each other). Remove the cycle to fix."
                    );
                    checkOutputFlag = false;
                    break;
                }
            }
            queue.addAll(toEnqueue);
        }

        finalizeExecution(nco, checkOutputFlag, reachedSuccessTerminal);

        log.info(
                "[FlowExecutionEngine] END executionId={} flowId={} status={}",
                executionId,
                flowId,
                nco.getMeta().getStatus()
        );

        return nco;
    }

    /**
     * Runs a pre-scoped list of branch nodes in sequence on the calling thread.
     *
     * CRITICAL: the nodes list must NOT contain the parent FORK node or any JOIN node.
     * Passing the full flow node list here would cause infinite recursion because each
     * branch would re-execute the FORK node and spawn another generation of branches.
     *
     * This method is called by ForkNodeExecutor from inside a CompletableFuture —
     * it runs on a dedicated pool thread, not the main flow thread.
     */
    public void executeBranch(
            List<FlowNode> nodes,
            NexflowContextObject branchNco,
            String executionId,
            String branchName
    ) {
        log.info("[FlowExecutionEngine] executeBranch START branch='{}' executionId='{}' nodeCount={}",
                branchName, executionId, nodes != null ? nodes.size() : 0);

        if (nodes == null || nodes.isEmpty()) {
            log.info("[FlowExecutionEngine] executeBranch END branch='{}' executionId='{}' (no nodes)", branchName, executionId);
            return;
        }

        for (FlowNode node : nodes) {

            // Safety guard: FORK/JOIN must never run inside a branch. If they appear here,
            // branch configuration is wrong. Skip with an error log instead of recursing.
            if (node.getNodeType() == NodeType.FORK || node.getNodeType() == NodeType.JOIN) {
                log.error("[FlowExecutionEngine] executeBranch RECURSION GUARD — skipping {} node '{}' in branch '{}'. " +
                                "The FORK/JOIN nodes must not appear in branch node lists. " +
                                "Fix branch configuration to remove nodeId='{}'.",
                        node.getNodeType(), node.getLabel(), branchName, node.getId());
                continue;
            }

            // Persist NodeExecution (RUNNING) so transaction detail can show branch node request/response
            NodeExecution nodeExecution = new NodeExecution();
            nodeExecution.setExecutionId(UUID.fromString(executionId));
            nodeExecution.setNodeId(node.getId());
            nodeExecution.setNodeLabel(node.getLabel());
            nodeExecution.setNodeType(node.getNodeType().name());
            nodeExecution.setBranchName(branchName);
            nodeExecution.setStatus(NodeExecutionStatus.RUNNING);
            nodeExecution.setInputNex(branchNco.getNex() != null ? new LinkedHashMap<>(branchNco.getNex()) : new LinkedHashMap<>());
            nodeExecution.setStartedAt(Instant.now());
            nodeExecutionPersistence.save(nodeExecution);

            eventPublisher.nodeStarted(executionId, node.getId().toString());

            Instant nodeStart = Instant.now();
            try {
                NodeContext result = runNode(node, branchNco, executionId);
                branchNco.setNodeOutput(node.getId().toString(), result);
                branchNco.setNodeAlias(toLabelKey(node.getLabel()), result);

                // Auto-populate branch nex for every non-VARIABLE, non-LOOP node
                if (result.getStatus() != NodeStatus.FAILURE && node.getNodeType() != NodeType.VARIABLE && node.getNodeType() != NodeType.LOOP) {
                    Object valueForNex = result.getSuccessOutput() != null ? result.getSuccessOutput() : result.getOutput();
                    if (valueForNex != null) {
                        // 1. Auto-add under camelCase label
                        String labelKey = toLabelKey(node.getLabel());
                        branchNco.getNex().putIfAbsent(labelKey, valueForNex);

                        // 2. Explicit saveOutputAs (overwrites)
                        String saveAs = extractSaveOutputAs(node);
                        if (saveAs != null && !saveAs.isBlank()) {
                            String key = saveAs.trim();
                            if (key.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                                branchNco.getNex().put(key, valueForNex);
                            }
                        }
                    }
                }

                if (result.getStatus() == NodeStatus.FAILURE) {
                    long durationMs = Duration.between(nodeStart, Instant.now()).toMillis();
                    nodeExecution.setStatus(NodeExecutionStatus.FAILURE);
                    nodeExecution.setFinishedAt(Instant.now());
                    nodeExecution.setDurationMs(durationMs);
                    nodeExecution.setOutputNex(branchNco.getNex() != null ? new LinkedHashMap<>(branchNco.getNex()) : new LinkedHashMap<>());
                    nodeExecution.setErrorMessage(result.getErrorMessage() != null ? result.getErrorMessage() : "Node returned FAILURE");
                    nodeExecutionPersistence.save(nodeExecution);

                    String err = result.getErrorMessage();
                    String message = (err != null && !err.isBlank())
                            ? err
                            : "Branch node '" + node.getLabel() + "' returned FAILURE";
                    eventPublisher.nodeError(executionId, node.getId().toString(), message);
                    throw new RuntimeException(message);
                }

                nodeExecution.setStatus(NodeExecutionStatus.SUCCESS);
                nodeExecution.setFinishedAt(Instant.now());
                nodeExecution.setDurationMs(Duration.between(nodeStart, Instant.now()).toMillis());
                nodeExecution.setOutputNex(branchNco.getNex() != null ? new LinkedHashMap<>(branchNco.getNex()) : new LinkedHashMap<>());
                nodeExecutionPersistence.save(nodeExecution);

                eventPublisher.nodeCompleted(executionId, node.getId().toString(), result.getStatus(), branchNco.getNex());

            } catch (RuntimeException e) {
                long durationMs = Duration.between(nodeStart, Instant.now()).toMillis();
                nodeExecution.setStatus(NodeExecutionStatus.FAILURE);
                nodeExecution.setFinishedAt(Instant.now());
                nodeExecution.setDurationMs(durationMs);
                nodeExecution.setErrorMessage(e.getMessage());
                nodeExecution.setOutputNex(branchNco.getNex() != null ? new LinkedHashMap<>(branchNco.getNex()) : new LinkedHashMap<>());
                nodeExecutionPersistence.save(nodeExecution);

                eventPublisher.nodeError(executionId, node.getId().toString(), e.getMessage());
                throw e;
            } catch (Exception e) {
                long durationMs = Duration.between(nodeStart, Instant.now()).toMillis();
                nodeExecution.setStatus(NodeExecutionStatus.FAILURE);
                nodeExecution.setFinishedAt(Instant.now());
                nodeExecution.setDurationMs(durationMs);
                nodeExecution.setErrorMessage(e.getMessage());
                nodeExecution.setOutputNex(branchNco.getNex() != null ? new LinkedHashMap<>(branchNco.getNex()) : new LinkedHashMap<>());
                nodeExecutionPersistence.save(nodeExecution);

                eventPublisher.nodeError(executionId, node.getId().toString(), e.getMessage());
                throw new RuntimeException(
                        "Branch '" + branchName + "' failed at node '" + node.getLabel() + "': " + e.getMessage(), e);
            }
        }

        log.info("[FlowExecutionEngine] executeBranch END branch='{}' executionId='{}'", branchName, executionId);
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
        // START is the only node that gets the original trigger payload.
        // We store it here once so the main loop does not overwrite it later.
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

        // nex.start — full trigger output {body: {...}} for backward compat ({{nex.start.body.field}})
        nco.getNex().putIfAbsent("start", output);

        // Also spread every top-level trigger body field directly into nex for flat access:
        // {{nex.userId}}, {{nex.orderId}}, etc. putIfAbsent so explicit saveOutputAs on a node is never clobbered.
        body.forEach((k, v) -> nco.getNex().putIfAbsent(k, v));

        // If START node has "Save output as", put trigger payload in nex under that key
        String saveAs = extractSaveOutputAs(startNode);
        if (saveAs != null && !saveAs.isBlank()) {
            String key = saveAs.trim();
            if (key.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                nco.getNex().put(key, output);
            }
        }
    }

    /**
     * Converts a node label to a camelCase key for {{}} refs.
     *
     * Multi-word labels: standard camelCase — "Calculate Discount" → "calculateDiscount".
     * Single-word labels: lowercase only the first character so user-written camelCase
     *   labels like "generateData" stay as "generateData" instead of becoming "generatedata".
     *   Examples: "generateData" → "generateData", "Orders" → "orders", "TOTAL" → "tOTAL".
     */
    public static String toLabelKey(String label) {
        if (label == null || label.isBlank()) return "node";
        String[] words = label.trim().replaceAll("[^a-zA-Z0-9 ]", "").split("\\s+");
        if (words.length == 0 || words[0].isBlank()) return "node";

        if (words.length == 1) {
            // Single word — lowercase only the first character to preserve camelCase intent
            String w = words[0];
            return Character.toLowerCase(w.charAt(0)) + w.substring(1);
        }

        // Multi-word — standard camelCase: first word all-lowercase, rest Title-case
        StringBuilder key = new StringBuilder(words[0].toLowerCase());
        for (int i = 1; i < words.length; i++) {
            if (!words[i].isBlank()) {
                key.append(Character.toUpperCase(words[i].charAt(0)));
                key.append(words[i].substring(1).toLowerCase());
            }
        }
        return key.toString();
    }

    /**
     * Adds all node IDs that were executed inside the FORK's branches to executedNodeIds and branchOnlyExecutedNodeIds.
     * This prevents the main loop from re-running those nodes when it reaches the JOIN, and allows us to treat
     * "JOIN's next are only these branch nodes" as normal completion (not a loop).
     */
    @SuppressWarnings("unchecked")
    private void addForkBranchNodeIdsToExecuted(FlowNode forkNode, Set<UUID> executedNodeIds, Set<UUID> branchOnlyExecutedNodeIds) {
        Object raw = forkNode.getConfig() != null ? forkNode.getConfig().get("branchNodeIds") : null;
        if (!(raw instanceof Map<?, ?>)) return;
        Map<String, List<String>> branchNodeIds = (Map<String, List<String>>) raw;
        for (List<String> ids : branchNodeIds.values()) {
            if (ids == null) continue;
            for (String idStr : ids) {
                if (idStr == null || idStr.isBlank()) continue;
                try {
                    UUID id = UUID.fromString(idStr.trim());
                    executedNodeIds.add(id);
                    if (branchOnlyExecutedNodeIds != null) branchOnlyExecutedNodeIds.add(id);
                } catch (IllegalArgumentException ignored) { /* skip invalid UUIDs */ }
            }
        }
    }

    /**
     * Ensures each FORK node has complete branchNodeIds for execution.
     *
     * Strategy per branch:
     * - If the branch already has a non-empty node list saved (by the frontend), keep it.
     *   The frontend cascade correctly builds this list; we trust it.
     * - If the branch has NO nodes (empty list or missing), derive them via BFS from the
     *   branch handle's entry edge, stopping at JOIN/FORK/SUCCESS/FAILURE boundaries.
     *   This covers flows saved before the cascade UI existed or where the user skipped saving.
     *
     * SUCCESS and FAILURE terminal nodes are excluded from branch lists — they are main-flow
     * terminals and must run on the main engine thread after JOIN, not inside branches.
     */
    @SuppressWarnings("unchecked")
    private void ensureForkBranchNodeIdsFromEdges(List<FlowNode> allNodes, List<FlowEdge> allEdges) {
        Map<UUID, FlowNode> nodeById = allNodes.stream()
                .collect(Collectors.toMap(FlowNode::getId, n -> n));

        // Pre-build outgoing adjacency so BFS is O(nodes+edges) not O(nodes*edges)
        Map<UUID, List<UUID>> outgoing = new HashMap<>();
        for (FlowEdge e : allEdges) {
            outgoing.computeIfAbsent(e.getSourceNodeId(), k -> new ArrayList<>())
                    .add(e.getTargetNodeId());
        }

        for (FlowNode node : allNodes) {
            if (node.getNodeType() != NodeType.FORK) continue;

            Map<String, Object> config = node.getConfig();
            if (config == null) {
                config = new HashMap<>();
                node.setConfig(config);
            }

            // Read existing branchNodeIds (may have been set by frontend and persisted in DB)
            Map<String, List<String>> existingBranchNodeIds = null;
            Object rawExisting = config.get("branchNodeIds");
            if (rawExisting instanceof Map<?, ?>) {
                existingBranchNodeIds = (Map<String, List<String>>) rawExisting;
            }

            boolean anyBranchUpdated = false;
            Map<String, List<String>> merged = existingBranchNodeIds != null
                    ? new LinkedHashMap<>(existingBranchNodeIds)
                    : new LinkedHashMap<>();

            for (FlowEdge e : allEdges) {
                if (!e.getSourceNodeId().equals(node.getId())) continue;
                String handle = e.getSourceHandle();
                if (handle == null || handle.isBlank()) continue;
                String branchKey = handle.trim();

                // If this branch already has nodes, trust the frontend — skip BFS for it
                List<String> existing = merged.get(branchKey);
                if (existing != null && !existing.isEmpty()) continue;

                // Branch is empty/missing — derive via BFS
                List<String> bfsDerived = bfsForBranchEntry(e.getTargetNodeId(), nodeById, outgoing);
                if (!bfsDerived.isEmpty()) {
                    merged.put(branchKey, bfsDerived);
                    anyBranchUpdated = true;
                }
            }

            // Second pass: some edges from FORK may have been saved with null/blank sourceHandle
            // (e.g. when a branch is added in Studio but the handle wasn't persisted).
            // Assign them in order to branch names that still have no entry nodes.
            @SuppressWarnings("unchecked")
            List<String> allBranchNames = (List<String>) config.getOrDefault("branches", List.of());
            List<String> unmatchedBranches = allBranchNames.stream()
                    .filter(b -> !merged.containsKey(b) || merged.get(b).isEmpty())
                    .collect(Collectors.toList());

            if (!unmatchedBranches.isEmpty()) {
                List<FlowEdge> nullHandleEdges = allEdges.stream()
                        .filter(e -> e.getSourceNodeId().equals(node.getId()))
                        .filter(e -> { String h = e.getSourceHandle(); return h == null || h.isBlank(); })
                        .collect(Collectors.toList());

                for (int i = 0; i < Math.min(unmatchedBranches.size(), nullHandleEdges.size()); i++) {
                    String branchName = unmatchedBranches.get(i);
                    FlowEdge e = nullHandleEdges.get(i);
                    List<String> derived = bfsForBranchEntry(e.getTargetNodeId(), nodeById, outgoing);
                    if (!derived.isEmpty()) {
                        merged.put(branchName, derived);
                        anyBranchUpdated = true;
                        log.warn("[FlowExecutionEngine] FORK '{}' edge to '{}' has no sourceHandle — " +
                                "assigned to unmatched branch '{}' (fix in Studio: set branch handle on the edge).",
                                node.getLabel(),
                                nodeById.containsKey(e.getTargetNodeId())
                                        ? nodeById.get(e.getTargetNodeId()).getLabel() : e.getTargetNodeId(),
                                branchName);
                    }
                }
            }

            if (anyBranchUpdated) {
                config.put("branchNodeIds", merged);
                log.info("[FlowExecutionEngine] BFS filled missing branch nodes for FORK '{}': {}",
                        node.getLabel(),
                        merged.entrySet().stream()
                                .map(en -> en.getKey() + "=" + en.getValue().size() + " node(s)")
                                .collect(Collectors.joining(", ")));
            }
        }
    }

    /**
     * BFS from a branch entry node collecting all reachable nodes,
     * stopping (but not including) JOIN, FORK, SUCCESS, and FAILURE boundaries.
     */
    private List<String> bfsForBranchEntry(UUID startId, Map<UUID, FlowNode> nodeById, Map<UUID, List<UUID>> outgoing) {
        List<String> result = new ArrayList<>();
        Queue<UUID> bfsQueue = new LinkedList<>();
        Set<UUID> visited = new HashSet<>();
        bfsQueue.add(startId);

        while (!bfsQueue.isEmpty()) {
            UUID curr = bfsQueue.poll();
            if (visited.contains(curr)) continue;
            visited.add(curr);

            FlowNode currNode = nodeById.get(curr);
            if (currNode == null) continue;
            // Stop at boundaries — these run on the main thread, not inside branches
            if (currNode.getNodeType() == NodeType.JOIN
                    || currNode.getNodeType() == NodeType.FORK
                    || currNode.getNodeType() == NodeType.SUCCESS
                    || currNode.getNodeType() == NodeType.FAILURE) continue;

            result.add(curr.toString());

            for (UUID next : outgoing.getOrDefault(curr, List.of())) {
                if (!visited.contains(next)) bfsQueue.add(next);
            }
        }
        return result;
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

    private void finalizeExecution(NexflowContextObject nco, boolean noFailure, boolean reachedSuccessTerminal) {
        nco.getMeta().setCompletedAt(Instant.now());
        // Success terminal wins. Otherwise any failure means the overall run fails.
        // If any path reached a SUCCESS terminal (e.g. after JOIN with onBranchFailure=CONTINUE), treat run as SUCCESS
        if (reachedSuccessTerminal) {
            nco.getMeta().setStatus(ExecutionStatus.SUCCESS);
        } else if (noFailure) {
            nco.getMeta().setStatus(ExecutionStatus.SUCCESS);
        } else {
            nco.getMeta().setStatus(ExecutionStatus.FAILURE);
        }
    }
}
