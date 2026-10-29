package com.nexflow.nexflow_backend.executor.impl;

import com.nexflow.nexflow_backend.engine.FlowExecutionEngine;
import com.nexflow.nexflow_backend.engine.FlowExecutionEngine.PathResult;
import com.nexflow.nexflow_backend.engine.FriendlyErrors;
import com.nexflow.nexflow_backend.model.domain.BranchExecutionStatus;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.JoinStrategy;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.dto.BranchResult;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.repository.NodeExecutor;
import com.nexflow.nexflow_backend.service.BranchExecutionPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs a FORK: every branch walks the graph in parallel until it reaches the JOIN, then the
 * results are merged into the main nex and the main path continues from the JOIN.
 *
 * Settings (node config):
 *   strategy         WAIT_ALL (default) | WAIT_FIRST (first success wins) | WAIT_N (waitForCount successes)
 *   onBranchFailure  FAIL_FAST (default, any failed branch fails the fork) | CONTINUE (ignore failed branches)
 *   timeoutSeconds   how long to wait for branches (default 60)
 *   onTimeout        FAIL (default) | CONTINUE_WITH_PARTIAL
 *
 * A branch that fails stops at the failing step — its downstream steps never run.
 */
@Slf4j
@Component
public class ForkNodeExecutor implements NodeExecutor {

    // Provider breaks the cycle engine → NodeRunner → executors → engine.
    private final ObjectProvider<FlowExecutionEngine> engine;
    private final BranchExecutionPersistenceService branchPersistence;
    private final ThreadPoolTaskExecutor branchPool;

    public ForkNodeExecutor(ObjectProvider<FlowExecutionEngine> engine,
                            BranchExecutionPersistenceService branchPersistence,
                            @Qualifier("forkBranchExecutor") ThreadPoolTaskExecutor branchPool) {
        this.engine = engine;
        this.branchPersistence = branchPersistence;
        this.branchPool = branchPool;
    }

    @Override
    public NodeType supportedType() {
        return NodeType.FORK;
    }

    @Override
    public NodeContext execute(FlowNode fork, NexflowContextObject nco) {
        Map<String, List<FlowNode>> branches = nco.getGraph().branchEntries(fork);
        if (branches.isEmpty()) {
            return failure(fork, "This Fork has no branches. Add at least one branch and connect it to a step.");
        }
        Settings settings = Settings.from(fork, branches.size());
        long startedAt = System.currentTimeMillis();

        List<BranchResult> results = runBranches(branches, nco, settings);
        results.forEach(r -> branchPersistence.saveBranchExecution(nco.getMeta().getExecutionId(), fork.getId(), r));

        String problem = settings.judge(results);
        if (problem != null) return failure(fork, problem);

        mergeIntoNex(results, nco, settings, System.currentTimeMillis() - startedAt);
        List<String> joinIds = results.stream()
                .flatMap(r -> r.joins().stream())
                .distinct()
                .map(UUID::toString)
                .toList();
        return NodeContext.builder()
                .nodeId(fork.getId().toString())
                .nodeType(NodeType.FORK.name())
                .status(NodeStatus.SUCCESS)
                .output(Map.of("joinNodeIds", joinIds, "branches", branchSummary(results)))
                .build();
    }

    // ── Running branches ─────────────────────────────────────────────────────

    private List<BranchResult> runBranches(Map<String, List<FlowNode>> branches, NexflowContextObject nco, Settings s) {
        CompletionService<BranchResult> completion = new ExecutorCompletionService<>(branchPool.getThreadPoolExecutor());
        List<Future<BranchResult>> futures = new ArrayList<>();
        branches.forEach((name, entries) -> futures.add(completion.submit(() -> runBranch(name, entries, nco))));

        Map<String, BranchResult> finished = new LinkedHashMap<>();
        boolean timedOut = false;
        long deadline = System.currentTimeMillis() + s.timeoutSeconds * 1000L;
        int successes = 0;
        try {
            while (finished.size() < branches.size()) {
                long remaining = deadline - System.currentTimeMillis();
                Future<BranchResult> done = remaining > 0 ? completion.poll(remaining, TimeUnit.MILLISECONDS) : null;
                if (done == null) {
                    timedOut = true;
                    break;
                }
                BranchResult r = done.get();
                finished.put(r.branchName(), r);
                if (r.isFailure() && s.failFast) break;
                if (r.isSuccess() && ++successes >= s.requiredSuccesses && !s.waitForAll()) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.error("[Fork] branch task crashed", e); // runBranch catches everything, so this is unexpected
        } finally {
            futures.forEach(f -> f.cancel(true)); // stops branches that are no longer needed
        }

        boolean stoppedByTimeout = timedOut;
        return branches.keySet().stream()
                .map(name -> finished.getOrDefault(name, stoppedByTimeout
                        ? BranchResult.timeout(name, s.timeoutSeconds * 1000L)
                        : BranchResult.cancelled(name)))
                .toList();
    }

    private BranchResult runBranch(String name, List<FlowNode> entries, NexflowContextObject parent) {
        long start = System.currentTimeMillis();
        if (entries.isEmpty()) {
            return BranchResult.success(name, Map.of(), 0, Set.of());
        }
        try {
            NexflowContextObject branchNco = NexflowContextObject.forBranch(parent);
            PathResult path = engine.getObject().runBranch(entries, branchNco, name);
            long ms = System.currentTimeMillis() - start;
            return path.failed()
                    ? BranchResult.failure(name, path.errorMessage(), ms)
                    : BranchResult.success(name, branchNco.getNex(), ms, path.joinsReached());
        } catch (Exception e) {
            log.error("[Fork] branch '{}' crashed", name, e);
            return BranchResult.failure(name, FriendlyErrors.forException(e, null), System.currentTimeMillis() - start);
        }
    }

    // ── Merging ──────────────────────────────────────────────────────────────

    /**
     * Each successful branch's new values are available two ways:
     *   nex.&lt;branchName&gt;.key  — namespaced, never conflicts
     *   nex.key                  — flat, unless a value with that name existed before the fork
     *                              (first branch in configured order wins between branches)
     */
    private static void mergeIntoNex(List<BranchResult> results, NexflowContextObject nco, Settings s, long totalMs) {
        Map<String, Object> before = new LinkedHashMap<>(nco.getNex());
        for (BranchResult r : results) {
            if (!r.isSuccess()) continue;
            Map<String, Object> added = r.nex().entrySet().stream()
                    .filter(e -> !before.containsKey(e.getKey()) || before.get(e.getKey()) != e.getValue())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
            nco.getNex().put(r.branchName(), added);
            added.forEach((k, v) -> {
                if (!before.containsKey(k)) nco.getNex().putIfAbsent(k, v);
            });
        }
        Map<String, Object> joinMeta = new LinkedHashMap<>();
        joinMeta.put("totalParallelMs", totalMs);
        joinMeta.put("strategy", s.strategy.name());
        joinMeta.put("branchCount", results.size());
        joinMeta.putAll(branchSummary(results));
        nco.getNex().put("join", joinMeta);
    }

    private static Map<String, Object> branchSummary(List<BranchResult> results) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (BranchResult r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", r.status().name());
            m.put("durationMs", r.durationMs());
            if (r.errorMessage() != null) m.put("error", r.errorMessage());
            summary.put(r.branchName(), m);
        }
        return summary;
    }

    private static NodeContext failure(FlowNode fork, String message) {
        return NodeContext.builder()
                .nodeId(fork.getId().toString())
                .nodeType(NodeType.FORK.name())
                .status(NodeStatus.FAILURE)
                .errorMessage(message)
                .errorDetail(message)
                .build();
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    private record Settings(JoinStrategy strategy, int requiredSuccesses, boolean failFast,
                            int timeoutSeconds, boolean continueOnTimeout) {

        static Settings from(FlowNode fork, int branchCount) {
            JoinStrategy strategy = parseStrategy(text(fork, "strategy", "WAIT_ALL"));
            int n = switch (strategy) {
                case WAIT_ALL -> branchCount;
                case WAIT_FIRST -> 1;
                case WAIT_N -> Math.max(1, Math.min(branchCount,
                        number(fork, "waitForCount", number(fork, "waitForN", 2))));
            };
            return new Settings(
                    strategy,
                    n,
                    !"CONTINUE".equals(text(fork, "onBranchFailure", "FAIL_FAST")),
                    Math.max(1, number(fork, "timeoutSeconds", 60)),
                    "CONTINUE_WITH_PARTIAL".equals(text(fork, "onTimeout", "FAIL")));
        }

        boolean waitForAll() {
            return strategy == JoinStrategy.WAIT_ALL;
        }

        /** Plain-language reason the fork failed, or null if it may continue. */
        String judge(List<BranchResult> results) {
            List<BranchResult> failed = results.stream().filter(BranchResult::isFailure).toList();
            long succeeded = results.stream().filter(BranchResult::isSuccess).count();
            boolean anyTimeout = results.stream().anyMatch(r -> r.status() == BranchExecutionStatus.TIMEOUT);

            if (failFast && !failed.isEmpty()) {
                BranchResult first = failed.get(0);
                return "Branch '" + first.branchName() + "' failed: " + Objects.toString(first.errorMessage(), "no reason given");
            }
            if (anyTimeout && !continueOnTimeout) {
                return "The Fork waited " + timeoutSeconds + " seconds, but not all branches finished in time.";
            }
            if (succeeded == 0) {
                return "Every branch failed, so the flow can't continue. " + failed.stream()
                        .map(r -> "'" + r.branchName() + "': " + r.errorMessage())
                        .collect(Collectors.joining("; "));
            }
            if (!waitForAll() && succeeded < requiredSuccesses) {
                return "Only " + succeeded + " of the " + requiredSuccesses + " required branches succeeded.";
            }
            return null;
        }

        private static JoinStrategy parseStrategy(String raw) {
            try {
                return JoinStrategy.valueOf(raw);
            } catch (IllegalArgumentException e) {
                return JoinStrategy.WAIT_ALL;
            }
        }

        /** Normalised text setting: "fail-fast" / "fail_fast" / "Fail Fast" → "FAIL_FAST". */
        private static String text(FlowNode node, String key, String fallback) {
            Object v = node.configValue(key, fallback);
            return v.toString().trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        }

        private static int number(FlowNode node, String key, int fallback) {
            return node.configValue(key, null) instanceof Number n ? n.intValue() : fallback;
        }
    }
}
