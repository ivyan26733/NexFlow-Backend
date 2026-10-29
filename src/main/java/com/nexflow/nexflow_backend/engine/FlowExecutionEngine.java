package com.nexflow.nexflow_backend.engine;

import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeExecution;
import com.nexflow.nexflow_backend.model.domain.NodeExecutionStatus;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.repository.FlowEdgeRepository;
import com.nexflow.nexflow_backend.repository.FlowNodeRepository;
import com.nexflow.nexflow_backend.service.NodeExecutionPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Walks a flow graph from its START node and runs each step.
 *
 * Rules:
 *  - After a step succeeds, its success (and unconditional) connections run next.
 *  - After a step fails, only its error connection runs. With no error connection the whole run
 *    stops and is marked FAILURE — nothing further down the success path executes.
 *  - A DECISION that evaluates to false follows its "false" connection, or simply ends that path.
 *  - Reaching a FAILURE end step marks the run FAILURE.
 *  - A LOOP that repeats re-runs its body; every other circle in a flow is rejected up front.
 *  - A FORK runs its branches in parallel (see ForkNodeExecutor); the run continues from the JOIN.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowExecutionEngine {

    /** Hard cap on steps per path so a runaway loop ends cleanly instead of running forever. */
    static final int MAX_STEPS = 5_000;

    private final FlowNodeRepository nodeRepository;
    private final FlowEdgeRepository edgeRepository;
    private final NodeRunner nodeRunner;
    private final NodeExecutionPersistenceService nodeExecutionPersistence;

    /** Outcome of walking a path (the main flow or one FORK branch). */
    public record PathResult(boolean failed, String errorMessage, String errorDetail, Set<UUID> joinsReached) {
        static PathResult ok(Set<UUID> joins) {
            return new PathResult(false, null, null, joins);
        }

        static PathResult fail(String message, String detail) {
            return new PathResult(true, message, detail, Set.of());
        }
    }

    public NexflowContextObject execute(UUID flowId, String executionId, Map<String, Object> triggerPayload, UUID userId) {
        FlowGraph graph = new FlowGraph(flowId, nodeRepository.findByFlowId(flowId), edgeRepository.findByFlowId(flowId));
        return execute(graph, flowId, executionId, triggerPayload, userId);
    }

    /** Runs an already-loaded graph. Separate from loading so the engine can be tested without a database. */
    public NexflowContextObject execute(FlowGraph graph, UUID flowId, String executionId,
                                        Map<String, Object> triggerPayload, UUID userId) {
        NexflowContextObject nco = NexflowContextObject.create(flowId.toString(), executionId, userId);
        nco.setGraph(graph);
        nco.getProtectedNexKeys().addAll(graph.saveOutputAsKeys());
        log.info("[Engine] START executionId={} flowId={}", executionId, flowId);

        Optional<String> problem = graph.validate();
        PathResult result;
        if (problem.isPresent()) {
            result = PathResult.fail(problem.get(), null);
        } else {
            injectTrigger(graph.start(), nco, triggerPayload);
            result = walk(graph.next(graph.start(), nco.getNodeOutput(graph.start().getId().toString())), nco, null);
        }

        nco.getMeta().setCompletedAt(Instant.now());
        nco.getMeta().setStatus(result.failed() ? ExecutionStatus.FAILURE : ExecutionStatus.SUCCESS);
        nco.getMeta().setErrorMessage(result.errorMessage());
        nco.getMeta().setErrorDetail(result.errorDetail());
        log.info("[Engine] END executionId={} status={}", executionId, nco.getMeta().getStatus());
        return nco;
    }

    /** Runs one FORK branch on the calling thread, stopping at the JOIN it converges on. */
    public PathResult runBranch(List<FlowNode> entries, NexflowContextObject branchNco, String branchName) {
        return walk(entries, branchNco, branchName);
    }

    /**
     * Breadth-first walk. A node runs at most once per walk, except inside a LOOP body, which is
     * reset every time the loop repeats. Inside a branch ({@code branchName != null}) JOIN nodes are
     * not run — they are reported back so the FORK knows where the main path continues.
     */
    private PathResult walk(List<FlowNode> entries, NexflowContextObject nco, String branchName) {
        FlowGraph graph = nco.getGraph();
        Deque<FlowNode> queue = new ArrayDeque<>();
        Set<UUID> queued = new HashSet<>();
        Set<UUID> done = new HashSet<>();
        Set<UUID> joins = new LinkedHashSet<>();
        int steps = 0;

        entries.forEach(n -> enqueue(n, queue, queued, done));
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                return PathResult.fail("This run was stopped before it finished.", null);
            }
            FlowNode node = queue.poll();
            queued.remove(node.getId());

            if (branchName != null && node.getNodeType() == NodeType.JOIN) {
                joins.add(node.getId());
                continue;
            }
            if (++steps > MAX_STEPS) {
                return PathResult.fail("This flow ran more than " + MAX_STEPS + " steps and was stopped. "
                        + "A loop is probably repeating forever — check its condition.", null);
            }

            NodeContext result = runAndRecord(node, nco, branchName);
            done.add(node.getId());

            if (node.getNodeType() == NodeType.FAILURE) {
                return PathResult.fail(result.getErrorMessage(), null);
            }
            if (result.getStatus() == NodeStatus.CONTINUE) {
                done.removeAll(graph.loopBody(node.getId()));
                done.remove(node.getId());
            }

            List<FlowNode> next = node.getNodeType() == NodeType.FORK && result.getStatus() == NodeStatus.SUCCESS
                    ? joinsFrom(result, graph)
                    : graph.next(node, result);

            if (result.isError() && next.isEmpty()) {
                return PathResult.fail(
                        "The step '" + node.displayName() + "' failed: " + result.getErrorMessage(),
                        result.getErrorDetail());
            }
            next.forEach(n -> enqueue(n, queue, queued, done));
        }
        return PathResult.ok(joins);
    }

    private static void enqueue(FlowNode node, Deque<FlowNode> queue, Set<UUID> queued, Set<UUID> done) {
        if (node != null && !done.contains(node.getId()) && queued.add(node.getId())) queue.add(node);
    }

    /** After a successful FORK the main path continues from the JOIN(s) its branches reached. */
    private static List<FlowNode> joinsFrom(NodeContext forkResult, FlowGraph graph) {
        Object ids = forkResult.getOutput() != null ? forkResult.getOutput().get("joinNodeIds") : null;
        if (!(ids instanceof List<?> list)) return List.of();
        return list.stream().map(id -> graph.node(UUID.fromString(id.toString()))).toList();
    }

    // ── Running and recording a single node ──────────────────────────────────

    private NodeContext runAndRecord(FlowNode node, NexflowContextObject nco, String branchName) {
        Instant started = Instant.now();
        Map<String, Object> nexBefore = branchName != null ? new LinkedHashMap<>(nco.getNex()) : null;

        NodeContext result = nodeRunner.run(node, nco);
        recordOutput(node, result, nco);

        if (branchName != null) {
            persistBranchNode(node, result, nco, branchName, started, nexBefore);
        }
        if (result.isError()) {
            log.warn("[Engine] node '{}' failed: {} | {}", node.displayName(), result.getErrorMessage(), result.getErrorDetail());
        }
        return result;
    }

    /** Makes a node's result available to later nodes ({{nodes.x}}, {{nex.x}}) and to the transaction log. */
    private static void recordOutput(FlowNode node, NodeContext result, NexflowContextObject nco) {
        String id = node.getId().toString();
        nco.setNodeOutput(id, result);
        nco.setNodeAlias(node.labelKey(), result);
        nco.getNodeExecutionOrder().add(id);

        // VARIABLE and LOOP nodes manage their own nex entries.
        if (node.getNodeType() == NodeType.VARIABLE || node.getNodeType() == NodeType.LOOP) return;
        Object value = result.getSuccessOutput() != null ? result.getSuccessOutput() : result.getOutput();
        if (value == null) return;

        String saveAs = node.saveOutputAsKey();
        String labelKey = node.labelKey();
        // Overwrite (not putIfAbsent) so a node re-run inside a loop exposes its latest output,
        // unless another node explicitly saves under that name or it is a trigger field.
        if (labelKey.equals(saveAs) || !nco.getProtectedNexKeys().contains(labelKey)) {
            nco.getNex().put(labelKey, value);
        }
        if (saveAs != null) nco.getNex().put(saveAs, value);
    }

    /** START is never executed: its output is the trigger payload, under output.body. */
    private static void injectTrigger(FlowNode start, NexflowContextObject nco, Map<String, Object> payload) {
        Map<String, Object> body = payload != null ? new HashMap<>(payload) : new HashMap<>();
        Map<String, Object> output = new HashMap<>();
        output.put("body", body);

        NodeContext startCtx = NodeContext.builder()
                .nodeId(start.getId().toString())
                .nodeType(NodeType.START.name())
                .status(NodeStatus.SUCCESS)
                .output(output)
                .build();
        nco.setNodeOutput(start.getId().toString(), startCtx);
        nco.setNodeAlias("start", startCtx);
        nco.getNodeExecutionOrder().add(start.getId().toString());

        // {{nex.start.body.x}} for older flows, and every trigger field flat: {{nex.orderId}}
        nco.getNex().put("start", output);
        body.forEach(nco.getNex()::putIfAbsent);
        nco.getProtectedNexKeys().addAll(body.keySet());
        nco.getProtectedNexKeys().add("start");

        String saveAs = start.saveOutputAsKey();
        if (saveAs != null) nco.getNex().put(saveAs, output);
    }

    private void persistBranchNode(FlowNode node, NodeContext result, NexflowContextObject nco, String branchName,
                                   Instant started, Map<String, Object> nexBefore) {
        NodeExecution record = new NodeExecution();
        record.setExecutionId(UUID.fromString(nco.getMeta().getExecutionId()));
        record.setNodeId(node.getId());
        record.setNodeLabel(node.displayName());
        record.setNodeType(node.getNodeType().name());
        record.setBranchName(branchName);
        record.setStatus(result.isError() ? NodeExecutionStatus.FAILURE : NodeExecutionStatus.SUCCESS);
        record.setInputNex(nexBefore);
        record.setOutputNex(new LinkedHashMap<>(nco.getNex()));
        record.setErrorMessage(result.getErrorMessage());
        record.setErrorDetail(result.getErrorDetail());
        record.setStartedAt(started);
        record.setFinishedAt(Instant.now());
        record.setDurationMs(Duration.between(started, record.getFinishedAt()).toMillis());
        nodeExecutionPersistence.save(record);
    }
}
