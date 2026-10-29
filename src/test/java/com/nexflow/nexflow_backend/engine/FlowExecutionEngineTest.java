package com.nexflow.nexflow_backend.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.EdgeCondition;
import com.nexflow.nexflow_backend.executor.LoopExecutor;
import com.nexflow.nexflow_backend.executor.NodeExecutorRegistry;
import com.nexflow.nexflow_backend.executor.ReferenceResolver;
import com.nexflow.nexflow_backend.executor.impl.ForkNodeExecutor;
import com.nexflow.nexflow_backend.executor.impl.JoinNodeExecutor;
import com.nexflow.nexflow_backend.model.domain.FlowEdge;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.repository.NodeExecutor;
import com.nexflow.nexflow_backend.service.BranchExecutionPersistenceService;
import com.nexflow.nexflow_backend.service.NodeExecutionPersistenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Engine behaviour end to end with real LOOP / FORK / JOIN executors and a fake "work" executor
 * (registered for NEXUS) whose outcome is driven by node config.
 */
class FlowExecutionEngineTest {

    private final List<FlowNode> nodes = new ArrayList<>();
    private final List<FlowEdge> edges = new ArrayList<>();
    private final Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final UUID flowId = UUID.randomUUID();

    private ThreadPoolTaskExecutor branchPool;
    private FlowExecutionEngine engine;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        branchPool = new ThreadPoolTaskExecutor();
        branchPool.setCorePoolSize(4);
        branchPool.initialize();

        @SuppressWarnings("unchecked")
        ObjectProvider<FlowExecutionEngine> engineProvider = mock(ObjectProvider.class);
        List<NodeExecutor> executors = List.of(
                new WorkExecutor(),
                new Terminal(NodeType.SUCCESS),
                new Terminal(NodeType.FAILURE),
                new FalseDecision(),
                new LoopExecutor(new ReferenceResolver(), mapper),
                new ForkNodeExecutor(engineProvider, mock(BranchExecutionPersistenceService.class), branchPool),
                new JoinNodeExecutor());
        NodeExecutorRegistry registry = new NodeExecutorRegistry(executors);
        registry.init();

        engine = new FlowExecutionEngine(null, null, new NodeRunner(registry, mapper),
                mock(NodeExecutionPersistenceService.class));
        when(engineProvider.getObject()).thenReturn(engine);
    }

    @AfterEach
    void tearDown() {
        branchPool.shutdown();
    }

    // ── Basic routing ────────────────────────────────────────────────────────

    @Test
    void linearFlowRunsEveryStepOnceAndSucceeds() {
        FlowNode start = node("Start", NodeType.START);
        FlowNode a = work("A");
        FlowNode b = work("B");
        FlowNode done = node("Done", NodeType.SUCCESS);
        connect(start, a);
        connect(a, b, EdgeCondition.SUCCESS, "success");
        connect(b, done, EdgeCondition.SUCCESS, "success");

        NexflowContextObject result = run();

        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(runCount("A")).isEqualTo(1);
        assertThat(runCount("B")).isEqualTo(1);
        assertThat(result.getNodeExecutionOrder()).containsExactly(
                start.getId().toString(), a.getId().toString(), b.getId().toString(), done.getId().toString());
    }

    @Test
    void failedStepStopsTheSuccessPathAndFailsTheRun() {
        FlowNode start = node("Start", NodeType.START);
        FlowNode a = work("A", Map.of("fail", true));
        FlowNode b = work("B");
        FlowNode done = node("Done", NodeType.SUCCESS);
        connect(start, a);
        connect(a, b, EdgeCondition.SUCCESS, "success");
        connect(b, done, EdgeCondition.SUCCESS, "success");

        NexflowContextObject result = run();

        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.FAILURE);
        assertThat(runCount("B")).isZero();
        assertThat(result.getMeta().getErrorMessage()).contains("'A' failed");
        assertThat(result.getMeta().getErrorDetail()).isEqualTo("boom");
    }

    @Test
    void failedStepFollowsOnlyItsErrorConnection() {
        FlowNode start = node("Start", NodeType.START);
        FlowNode a = work("A", Map.of("fail", true));
        FlowNode next = work("Next");
        FlowNode handler = work("Handler");
        FlowNode done = node("Done", NodeType.SUCCESS);
        connect(start, a);
        connect(a, next, EdgeCondition.SUCCESS, "success");
        connect(a, handler, EdgeCondition.FAILURE, "failure");
        connect(handler, done, EdgeCondition.SUCCESS, "success");

        NexflowContextObject result = run();

        assertThat(runCount("Next")).isZero();
        assertThat(runCount("Handler")).isEqualTo(1);
        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void unconditionalConnectionIsNotFollowedAfterAFailure() {
        FlowNode start = node("Start", NodeType.START);
        FlowNode a = work("A", Map.of("fail", true));
        FlowNode b = work("B");
        connect(start, a);
        connect(a, b, EdgeCondition.DEFAULT, null);

        NexflowContextObject result = run();

        assertThat(runCount("B")).isZero();
        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.FAILURE);
    }

    @Test
    void reachingTheFailureEndStepFailsTheRun() {
        FlowNode start = node("Start", NodeType.START);
        FlowNode a = work("A", Map.of("fail", true));
        FlowNode failed = node("Payment declined", NodeType.FAILURE);
        connect(start, a);
        connect(a, failed, EdgeCondition.FAILURE, "failure");

        NexflowContextObject result = run();

        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.FAILURE);
        assertThat(result.getMeta().getErrorMessage()).contains("Payment declined");
    }

    @Test
    void falseDecisionWithoutFalseConnectionEndsQuietly() {
        FlowNode start = node("Start", NodeType.START);
        FlowNode decision = node("Is VIP?", NodeType.DECISION);
        FlowNode vip = work("VIP");
        connect(start, decision);
        connect(decision, vip, EdgeCondition.SUCCESS, "success");

        NexflowContextObject result = run();

        assertThat(runCount("VIP")).isZero();
        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void retriesAreExhaustedBeforeTheEngineRoutes() {
        FlowNode start = node("Start", NodeType.START);
        FlowNode flaky = work("Flaky", Map.of("failTimes", 2, "retry", Map.of("maxRetries", 2, "backoffMs", 1)));
        FlowNode handler = work("Handler");
        FlowNode after = work("After");
        connect(start, flaky);
        connect(flaky, handler, EdgeCondition.FAILURE, "failure");
        connect(flaky, after, EdgeCondition.SUCCESS, "success");

        NexflowContextObject result = run();

        assertThat(attempts.get("Flaky").get()).isEqualTo(3);
        assertThat(runCount("Handler")).isZero();
        assertThat(runCount("After")).isEqualTo(1);
        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    void circleOutsideALoopIsRejectedBeforeRunning() {
        FlowNode start = node("Start", NodeType.START);
        FlowNode a = work("A");
        FlowNode b = work("B");
        connect(start, a);
        connect(a, b, EdgeCondition.SUCCESS, "success");
        connect(b, a, EdgeCondition.SUCCESS, "success");

        NexflowContextObject result = run();

        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.FAILURE);
        assertThat(result.getMeta().getErrorMessage()).contains("circle");
        assertThat(runCount("A")).isZero();
    }

    // ── Loops ────────────────────────────────────────────────────────────────

    @Test
    void loopRepeatsItsWholeBodyThenExits() {
        FlowNode start = node("Start", NodeType.START);
        FlowNode loop = node("Repeat", NodeType.LOOP, Map.of("condition", "{{loop.index}} < 3"));
        FlowNode body1 = work("Body One");
        FlowNode body2 = work("Body Two");
        FlowNode after = work("After");
        connect(start, loop);
        connect(loop, body1, EdgeCondition.CONTINUE, "continue");
        connect(body1, body2, EdgeCondition.SUCCESS, "success");
        connect(body2, loop, EdgeCondition.SUCCESS, "success");
        connect(loop, after, EdgeCondition.SUCCESS, "exit");

        NexflowContextObject result = run();

        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(runCount("Body One")).isEqualTo(3);
        assertThat(runCount("Body Two")).isEqualTo(3);
        assertThat(runCount("After")).isEqualTo(1);
        NodeContext loopResult = result.getNodes().get(loop.getId().toString());
        assertThat(loopResult.getSuccessOutput()).containsEntry("iterationCount", 3);
        assertThat((List<?>) loopResult.getSuccessOutput().get("accumulated")).hasSize(3);
    }

    @Test
    void bodyStepsSeeTheCurrentPassIndex() {
        FlowNode start = node("Start", NodeType.START);
        FlowNode loop = node("Repeat", NodeType.LOOP, Map.of("condition", "{{loop.index}} < 2"));
        FlowNode body = work("Body", Map.of("echo", "{{loop.index}}"));
        connect(start, loop);
        connect(loop, body, EdgeCondition.CONTINUE, "continue");
        connect(body, loop, EdgeCondition.SUCCESS, "success");

        NexflowContextObject result = run();

        // the last pass is pass 1 (0-based); nex.body is overwritten each pass, not frozen at the first
        @SuppressWarnings("unchecked")
        Map<String, Object> body1 = (Map<String, Object>) result.getNex().get("body");
        assertThat(body1).containsEntry("echo", "1");
    }

    @Test
    void loopStopsAtItsIterationLimit() {
        FlowNode start = node("Start", NodeType.START);
        FlowNode loop = node("Forever", NodeType.LOOP, Map.of("condition", "true", "maxIterations", 5));
        FlowNode body = work("Body");
        FlowNode after = work("After");
        connect(start, loop);
        connect(loop, body, EdgeCondition.CONTINUE, "continue");
        connect(body, loop, EdgeCondition.SUCCESS, "success");
        connect(loop, after, EdgeCondition.SUCCESS, "exit");

        NexflowContextObject result = run();

        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.FAILURE);
        assertThat(result.getMeta().getErrorMessage()).contains("repeated 5 times");
        assertThat(runCount("Body")).isEqualTo(5);
        assertThat(runCount("After")).isZero();
    }

    @Test
    void failingStepInsideLoopBodyStopsTheLoop() {
        FlowNode start = node("Start", NodeType.START);
        FlowNode loop = node("Repeat", NodeType.LOOP, Map.of("condition", "{{loop.index}} < 5"));
        FlowNode body = work("Body", Map.of("failTimes", 100));
        connect(start, loop);
        connect(loop, body, EdgeCondition.CONTINUE, "continue");
        connect(body, loop, EdgeCondition.SUCCESS, "success");

        NexflowContextObject result = run();

        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.FAILURE);
        assertThat(attempts.get("Body").get()).isEqualTo(1);
    }

    // ── Fork / Join ──────────────────────────────────────────────────────────

    @Test
    void failingBranchDoesNotRunItsDownstreamStepsAndFailFastStopsTheFlow() {
        buildForkFlow("FAIL_FAST");

        NexflowContextObject result = run();

        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.FAILURE);
        assertThat(runCount("A1")).isEqualTo(1);
        assertThat(runCount("B2")).isZero();
        assertThat(runCount("After")).isZero();
        assertThat(result.getMeta().getErrorMessage()).contains("Branch 'b' failed");
    }

    @Test
    void continuePolicyMergesSuccessfulBranchesAndRunsAfterJoin() {
        buildForkFlow("CONTINUE");

        NexflowContextObject result = run();

        assertThat(result.getMeta().getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(runCount("B2")).isZero();
        assertThat(runCount("After")).isEqualTo(1);
        assertThat(result.getNex()).containsKey("a").containsKey("a1").containsKey("join");
        @SuppressWarnings("unchecked")
        Map<String, Object> join = (Map<String, Object>) result.getNex().get("join");
        assertThat(join.get("b").toString()).contains("FAILURE");
    }

    private void buildForkFlow(String onBranchFailure) {
        FlowNode start = node("Start", NodeType.START);
        FlowNode fork = node("Fork", NodeType.FORK,
                Map.of("branches", List.of("a", "b"), "onBranchFailure", onBranchFailure));
        FlowNode a1 = work("A1");
        FlowNode b1 = work("B1", Map.of("fail", true));
        FlowNode b2 = work("B2");
        FlowNode join = node("Join", NodeType.JOIN);
        FlowNode after = work("After");
        connect(start, fork);
        connect(fork, a1, EdgeCondition.SUCCESS, "a");
        connect(fork, b1, EdgeCondition.SUCCESS, "b");
        connect(a1, join, EdgeCondition.SUCCESS, "success");
        connect(b1, b2, EdgeCondition.SUCCESS, "success");
        connect(b2, join, EdgeCondition.SUCCESS, "success");
        connect(join, after, EdgeCondition.SUCCESS, null);
    }

    // ── Fixture helpers ──────────────────────────────────────────────────────

    private NexflowContextObject run() {
        return engine.execute(new FlowGraph(flowId, nodes, edges), flowId, UUID.randomUUID().toString(), Map.of(), null);
    }

    private int runCount(String label) {
        AtomicInteger n = runs.get(label);
        return n == null ? 0 : n.get();
    }

    private FlowNode work(String label) {
        return work(label, Map.of());
    }

    private FlowNode work(String label, Map<String, Object> config) {
        return node(label, NodeType.NEXUS, config);
    }

    private FlowNode node(String label, NodeType type) {
        return node(label, type, Map.of());
    }

    private FlowNode node(String label, NodeType type, Map<String, Object> config) {
        FlowNode n = new FlowNode();
        n.setId(UUID.randomUUID());
        n.setFlowId(flowId);
        n.setLabel(label);
        n.setNodeType(type);
        n.setConfig(new HashMap<>(config));
        nodes.add(n);
        return n;
    }

    private void connect(FlowNode from, FlowNode to) {
        connect(from, to, EdgeCondition.SUCCESS, null);
    }

    private void connect(FlowNode from, FlowNode to, EdgeCondition condition, String handle) {
        FlowEdge e = new FlowEdge();
        e.setId(UUID.randomUUID());
        e.setFlowId(flowId);
        e.setSourceNodeId(from.getId());
        e.setTargetNodeId(to.getId());
        e.setConditionType(condition);
        e.setSourceHandle(handle);
        edges.add(e);
    }

    // ── Fake executors ───────────────────────────────────────────────────────

    /** Stand-in for any real work step. Config: fail=true, failTimes=N (fail the first N attempts), echo=template. */
    private class WorkExecutor implements NodeExecutor {
        private final ReferenceResolver resolver = new ReferenceResolver();

        @Override
        public NodeType supportedType() {
            return NodeType.NEXUS;
        }

        @Override
        public NodeContext execute(FlowNode node, NexflowContextObject nco) {
            int attempt = attempts.computeIfAbsent(node.getLabel(), k -> new AtomicInteger()).incrementAndGet();
            int failTimes = node.configValue("failTimes", 0) instanceof Number n ? n.intValue() : 0;
            boolean fail = Boolean.TRUE.equals(node.configValue("fail", false)) || attempt <= failTimes;
            if (fail) {
                return NodeContext.builder().nodeId(node.getId().toString()).nodeType("NEXUS")
                        .status(NodeStatus.FAILURE).errorMessage("boom").build();
            }
            runs.computeIfAbsent(node.getLabel(), k -> new AtomicInteger()).incrementAndGet();
            Map<String, Object> out = new HashMap<>();
            out.put("ran", node.getLabel());
            Object echo = node.configValue("echo", null);
            if (echo != null) out.put("echo", resolver.resolve(echo.toString(), nco));
            return NodeContext.builder().nodeId(node.getId().toString()).nodeType("NEXUS")
                    .status(NodeStatus.SUCCESS).successOutput(out).build();
        }
    }

    private static class Terminal implements NodeExecutor {
        private final NodeType type;

        Terminal(NodeType type) {
            this.type = type;
        }

        @Override
        public NodeType supportedType() {
            return type;
        }

        @Override
        public NodeContext execute(FlowNode node, NexflowContextObject nco) {
            boolean failure = type == NodeType.FAILURE;
            return NodeContext.builder().nodeId(node.getId().toString()).nodeType(type.name())
                    .status(failure ? NodeStatus.FAILURE : NodeStatus.SUCCESS)
                    .errorMessage(failure ? "The flow reached its failure end step '" + node.getLabel() + "'." : null)
                    .build();
        }
    }

    private static class FalseDecision implements NodeExecutor {
        @Override
        public NodeType supportedType() {
            return NodeType.DECISION;
        }

        @Override
        public NodeContext execute(FlowNode node, NexflowContextObject nco) {
            return NodeContext.builder().nodeId(node.getId().toString()).nodeType("DECISION")
                    .status(NodeStatus.FAILURE).output(Map.of("result", false)).build();
        }
    }
}
