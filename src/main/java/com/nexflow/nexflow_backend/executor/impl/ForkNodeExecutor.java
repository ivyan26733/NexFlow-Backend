package com.nexflow.nexflow_backend.executor.impl;

import com.nexflow.nexflow_backend.engine.ExecutionEventPublisher;
import com.nexflow.nexflow_backend.engine.FlowExecutionEngine;
import com.nexflow.nexflow_backend.repository.NodeExecutor;
import com.nexflow.nexflow_backend.model.domain.BranchExecution;
import com.nexflow.nexflow_backend.model.domain.BranchExecutionStatus;
import com.nexflow.nexflow_backend.model.domain.FlowNode;
import com.nexflow.nexflow_backend.model.domain.JoinStrategy;
import com.nexflow.nexflow_backend.model.domain.NodeType;
import com.nexflow.nexflow_backend.model.dto.BranchResult;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.model.nco.NodeContext;
import com.nexflow.nexflow_backend.model.nco.NodeStatus;
import com.nexflow.nexflow_backend.repository.BranchExecutionRepository;
import com.nexflow.nexflow_backend.service.BranchExecutionPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Executes a FORK node: spawns parallel branches and merges their results.
 *
 * Design: waiting/merging happens here; JOIN is a visual convergence marker.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ForkNodeExecutor implements NodeExecutor {

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    // Use ObjectProvider to avoid a hard circular dependency with FlowExecutionEngine.
    private final ObjectProvider<FlowExecutionEngine> engineProvider;
    private final BranchExecutionRepository branchRepo;
    private final BranchExecutionPersistenceService branchPersistence;
    private final ExecutionEventPublisher eventPublisher;
    /** Dedicated pool for branch tasks so branches run in parallel (not shared with main flow thread). */
    @Qualifier("forkBranchExecutor")
    private final Executor forkBranchExecutor;

    @Override
    public NodeType supportedType() {
        return NodeType.FORK;
    }

    @Override
    public NodeContext execute(FlowNode node, NexflowContextObject nco) {
        Map<String, Object> cfg = node.getConfig();
        if (cfg == null) {
            log.error("[ForkNode] '{}' has no config.", node.getLabel());
            return failure(node, "FORK node has no config.");
        }

        @SuppressWarnings("unchecked")
        List<String> branchNames = (List<String>) cfg.getOrDefault("branches", List.of());
        if (branchNames.isEmpty()) {
            log.error("[ForkNode] '{}' has no branches configured.", node.getLabel());
            return failure(node, "FORK node has no branches configured.");
        }

        // Normalise config so frontend can send "fail_fast", "CONTINUE", "wait-all" etc.
        // Support both camelCase and snake_case keys (e.g. from different serialization)
        String onBranchFailureRaw = getString(cfg, "onBranchFailure",
                getString(cfg, "on_branch_failure", ""));
        String onBranchFailure = normalise(onBranchFailureRaw != null && !onBranchFailureRaw.isBlank()
                ? onBranchFailureRaw : "CONTINUE");
        String onTimeout = normalise(getString(cfg, "onTimeout", "FAIL"));
        String strategyStr = normalise(getString(cfg, "strategy", "WAIT_ALL"));

        int timeoutSeconds = getInt(cfg, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        int waitForN = getInt(cfg, "waitForN", getInt(cfg, "waitForCount", 2));
        // Only fail whole flow when explicitly FAIL_FAST; missing/blank/anything else = CONTINUE
        boolean failFast = "FAIL_FAST".equals(onBranchFailure);
        // Frontend sends ON TIMEOUT as either FAIL or CONTINUE_WITH_PARTIAL
        boolean continuePartial = "CONTINUE_WITH_PARTIAL".equals(onTimeout);

        JoinStrategy strategy;
        try {
            strategy = JoinStrategy.valueOf(strategyStr);
        } catch (IllegalArgumentException e) {
            log.warn("[ForkNode] Unknown strategy '{}', defaulting to WAIT_ALL", strategyStr);
            strategy = JoinStrategy.WAIT_ALL;
        }

        String executionId = nco.getMeta().getExecutionId();
        UUID forkNodeId = node.getId();

        log.info("[ForkNode] '{}' starting — branches={} strategy={} onBranchFailure={} failFast={} timeoutSeconds={}",
                node.getLabel(), branchNames, strategy, onBranchFailure, failFast, timeoutSeconds);

        eventPublisher.nodeStarted(executionId, node.getId().toString());

        // Shallow copies of nex per branch; branches will only add new keys
        Map<String, Map<String, Object>> branchNexCopies = branchNames.stream()
                .collect(Collectors.toMap(
                        name -> name,
                        name -> new LinkedHashMap<>(nco.getNex())
                ));

        long forkStartMs = System.currentTimeMillis();

        // Build node lookup map from NCO once, then resolve branch node lists up front.
        Map<UUID, FlowNode> nodeById = nco.getFlowNodes().stream()
                .collect(Collectors.toMap(FlowNode::getId, fn -> fn));

        Map<String, List<FlowNode>> resolvedBranchNodes = new LinkedHashMap<>();
        for (String branchName : branchNames) {
            List<FlowNode> branchNodes = resolveBranchNodes(node, branchName, nodeById);
            resolvedBranchNodes.put(branchName, branchNodes);
            log.info("[ForkNode] branch '{}' resolved {} nodes: {}",
                    branchName,
                    branchNodes.size(),
                    branchNodes.stream().map(FlowNode::getLabel).collect(Collectors.toList()));
            if (branchNodes.size() > 1) {
                log.warn("[ForkNode] branch '{}' has {} nodes — they run in sequence inside this branch (cumulative time). For parallel execution use one node per branch.",
                        branchName, branchNodes.size());
            }
        }
        // Debug: log whether branchNodeIds was present so FAIL_FAST can ever fire (branches need nodes to fail)
        Object rawBranchNodeIds = node.getConfig() != null ? node.getConfig().get("branchNodeIds") : null;
        log.info("[ForkNode] '{}' config.branchNodeIds present={} (branches need nodes connected from branch handles for branch failures to be detected)",
                node.getLabel(), rawBranchNodeIds != null);

        // Launch all branches on dedicated executor — each with its own scoped node list
        List<CompletableFuture<BranchResult>> futures = branchNames.stream()
                .map(branchName -> CompletableFuture.supplyAsync(
                        () -> runBranch(
                                branchName,
                                resolvedBranchNodes.get(branchName),
                                nco,
                                branchNexCopies.get(branchName),
                                executionId,
                                forkNodeId
                        ),
                        forkBranchExecutor
                ))
                .toList();

        List<BranchResult> results;
        try {
            results = applyStrategy(strategy, futures, waitForN, timeoutSeconds, continuePartial, failFast, branchNames);
        } catch (Exception e) {
            log.error("[ForkNode] '{}' strategy execution failed: {}", node.getLabel(), e.getMessage());
            cancelAllFutures(futures);
            eventPublisher.nodeError(executionId, node.getId().toString(), e.getMessage());
            return failure(node, e.getMessage());
        }

        long totalParallelMs = System.currentTimeMillis() - forkStartMs;

        int totalBranches = branchNames.size();
        // Persist all branch records from the main flow thread so each save runs in its own transaction (REQUIRES_NEW)
        log.info("[ForkNode] ALL BRANCHES COMPLETE — executionId={} forkNodeId={} totalResults={} (expected {})",
                executionId, forkNodeId, results.size(), totalBranches);
        for (BranchResult r : results) {
            log.info("[ForkNode] result: branch='{}' success={} durationMs={} nexKeys={}",
                    r.getBranchName(),
                    r.isSuccess(),
                    r.getDurationMs(),
                    r.isSuccess() && r.getNex() != null ? r.getNex().keySet() : "n/a");
        }
        for (BranchResult r : results) {
            branchPersistence.saveBranchExecution(
                    executionId,
                    forkNodeId,
                    r.getBranchName(),
                    r.getStatus(),
                    r.getDurationMs(),
                    r.isSuccess() ? r.getNex() : null,
                    r.getErrorMessage()
            );
        }
        List<BranchExecution> savedRecords = branchRepo.findByExecutionIdAndForkNodeId(
                UUID.fromString(executionId), forkNodeId);
        log.info("[ForkNode] DB check — {} branch records found for this fork node (expected {})",
                savedRecords.size(), totalBranches);
        savedRecords.forEach(rec ->
                log.info("[ForkNode] DB record: branch='{}' status={}",
                        rec.getBranchName(), rec.getStatus())
        );

        List<BranchResult> succeeded = results.stream().filter(BranchResult::isSuccess).toList();
        List<BranchResult> failures = results.stream().filter(BranchResult::isFailure).toList();

        log.info("[ForkNode] '{}' completed — succeeded={} failed={} onBranchFailure={}",
                node.getLabel(), succeeded.size(), failures.size(), onBranchFailure);

        if (!failures.isEmpty()) {
            if (failFast) {
                String errorSummary = failures.stream()
                        .map(r -> r.getBranchName() + ": " + r.getErrorMessage())
                        .collect(Collectors.joining(", "));

                // Fail‑fast semantics: cancel any still‑running branch futures so they don't do useless work
                cancelAllFutures(futures);

                log.error("[ForkNode] '{}' FAIL_FAST — failed branches: {}", node.getLabel(), errorSummary);
                eventPublisher.nodeError(executionId, node.getId().toString(), errorSummary);
                return failure(node, errorSummary);
            } else {
                // CONTINUE — log but proceed with succeeded branches only
                failures.forEach(r ->
                        log.warn("[ForkNode] '{}' branch '{}' failed but CONTINUE policy — skipping. Error: {}",
                                node.getLabel(), r.getBranchName(), r.getErrorMessage())
                );
            }
        }

        // WAIT_N quorum: require at least waitForN succeeded branches
        if (JoinStrategy.WAIT_N.equals(strategy) && succeeded.size() < waitForN) {
            log.error("[ForkNode] '{}' WAIT_N={} but only {} branches succeeded",
                    node.getLabel(), waitForN, succeeded.size());
            eventPublisher.nodeError(executionId, node.getId().toString(),
                    "WAIT_N quorum not met: only " + succeeded.size() + " succeeded");
            return failure(node, "WAIT_N quorum not met");
        }

        // For WAIT_N: merge the fastest N succeeded branches into nex; persist all results
        List<BranchResult> toMerge = succeeded;
        if (JoinStrategy.WAIT_N.equals(strategy)) {
            if (succeeded.size() > waitForN) {
                toMerge = succeeded.stream()
                        .sorted(Comparator.comparingLong(BranchResult::getDurationMs))
                        .limit(waitForN)
                        .collect(Collectors.toList());
            }
            log.info("[ForkNode] WAIT_N={} quorum met — using {} branch(es)", waitForN, toMerge.size());
        }

        // Merge successful branch outputs into parent nex
        toMerge.forEach(r -> nco.getNex().put(r.getBranchName(), r.getNex()));

        // Write timing and status metadata under nex.join
        Map<String, Object> joinMeta = new LinkedHashMap<>();
        joinMeta.put("totalParallelMs", totalParallelMs);
        joinMeta.put("strategy", strategy.name());
        joinMeta.put("branchCount", branchNames.size());
        results.forEach(r -> {
            Map<String, Object> branchMeta = new LinkedHashMap<>();
            branchMeta.put("status", r.getStatus().name());
            branchMeta.put("durationMs", r.getDurationMs());
            if (r.getErrorMessage() != null) {
                branchMeta.put("error", r.getErrorMessage());
            }
            joinMeta.put(r.getBranchName(), branchMeta);
        });
        nco.getNex().put("join", joinMeta);

        log.info("[ForkNode] '{}' completed — parallel={}ms branches={}",
                node.getLabel(), totalParallelMs, branchNames);

        eventPublisher.nodeCompleted(executionId, node.getId().toString(), NodeStatus.SUCCESS, nco.getNex());

        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(NodeType.FORK.name())
                .status(NodeStatus.SUCCESS)
                .build();
    }

    private BranchResult runBranch(
            String branchName,
            List<FlowNode> branchNodes,
            NexflowContextObject parentNco,
            Map<String, Object> branchNex,
            String executionId,
            UUID forkNodeId) {

        long startMs = System.currentTimeMillis();

        // Empty branch — no nodes configured yet, succeed immediately with empty nex
        if (branchNodes == null || branchNodes.isEmpty()) {
            log.warn("[ForkNode] Branch '{}' has no configured nodes — completing with empty nex.", branchName);
            eventPublisher.branchCompleted(executionId, forkNodeId.toString(),
                    branchName, NodeStatus.SUCCESS, 0L, null);
            return BranchResult.success(branchName, new LinkedHashMap<>(), 0L);
        }

        try {
            // Build isolated branch NCO; share meta but override nex
            NexflowContextObject branchNco = NexflowContextObject.forBranch(
                    parentNco,
                    branchNex != null ? branchNex : new LinkedHashMap<>(),
                    branchName
            );

            eventPublisher.branchStarted(executionId, forkNodeId.toString(), branchName);

            // Execute ONLY the pre-resolved branch nodes — not the full flow
            engineProvider.getObject().executeBranch(branchNodes, branchNco, executionId, branchName);

            long durationMs = System.currentTimeMillis() - startMs;
            eventPublisher.branchCompleted(executionId, forkNodeId.toString(), branchName, NodeStatus.SUCCESS, durationMs, null);

            log.info("[ForkNode] Branch '{}' SUCCESS in {}ms", branchName, durationMs);
            return BranchResult.success(branchName, branchNco.getNex(), durationMs);

        } catch (Throwable t) {
            // Catch everything so the CompletableFuture never completes exceptionally —
            // otherwise allOf().join() would throw and onBranchFailure would never be applied.
            long durationMs = System.currentTimeMillis() - startMs;
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            log.error("[ForkNode] Branch '{}' FAILED after {}ms: {}", branchName, durationMs, msg, t);
            eventPublisher.branchCompleted(executionId, forkNodeId.toString(), branchName, NodeStatus.FAILURE, durationMs, msg);
            return BranchResult.failure(branchName, msg, durationMs);
        }
    }

    private List<BranchResult> applyStrategy(
            JoinStrategy strategy,
            List<CompletableFuture<BranchResult>> futures,
            int waitForCount,
            int timeoutSeconds,
            boolean continuePartial,
            boolean failFast,
            List<String> branchNames) throws Exception {

        switch (strategy) {
            case WAIT_ALL -> {
                CompletableFuture<Void> allDone = CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture[0]));
                try {
                    allDone.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    if (!continuePartial) {
                        throw new RuntimeException(
                                "Fork timed out after " + timeoutSeconds + "s waiting for all branches");
                    }
                    log.warn("[ForkNode] Timeout reached, continuing with partial results");
                    return collectWithTimeout(futures, branchNames);
                }
                return futures.stream().map(CompletableFuture::join).toList();
            }
            case WAIT_FIRST -> {
                // Race mode:
                // - Default: first SUCCESS wins, other branches are cancelled.
                // - With FAIL_FAST enabled: if any branch fails before a success arrives, fail the whole FORK immediately.

                CompletableFuture<BranchResult> firstSuccess = new CompletableFuture<>();

                futures.forEach(f -> f.whenComplete((result, ex) -> {
                    if (firstSuccess.isDone()) {
                        return;
                    }
                    BranchResult r = result != null
                            ? result
                            : BranchResult.failure("unknown",
                                    ex != null && ex.getMessage() != null ? ex.getMessage() : "unknown", 0);

                    if (failFast && r.isFailure()) {
                        // Short‑circuit the whole fork on first branch failure
                        firstSuccess.completeExceptionally(new RuntimeException(
                                "FAIL_FAST: branch '" + r.getBranchName() + "' failed: " + r.getErrorMessage()));
                        return;
                    }

                    if (r.isSuccess()) {
                        firstSuccess.complete(r);
                    }
                }));

                try {
                    BranchResult winner = firstSuccess.get(timeoutSeconds, TimeUnit.SECONDS);
                    cancelAllFutures(futures);
                    log.info("[ForkNode] WAIT_FIRST — winner: '{}' in {}ms",
                            winner.getBranchName(), winner.getDurationMs());
                    // Winner + cancelled placeholders so merge and updateBranchRecords see full picture
                    List<BranchResult> list = new ArrayList<>();
                    list.add(winner);
                    for (String name : branchNames) {
                        if (!name.equals(winner.getBranchName())) {
                            list.add(BranchResult.cancelled(name));
                        }
                    }
                    return list;
                } catch (TimeoutException e) {
                    log.warn("[ForkNode] WAIT_FIRST timeout after {}s", timeoutSeconds);
                    cancelAllFutures(futures);
                    if (!continuePartial) {
                        throw new RuntimeException("Fork WAIT_FIRST timed out after " + timeoutSeconds + "s");
                    }
                    return collectWithTimeout(futures, branchNames);
                } catch (ExecutionException e) {
                    // FAIL_FAST path: a branch failed before any success
                    cancelAllFutures(futures);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw new RuntimeException(cause.getMessage(), cause);
                }
            }
            case WAIT_N -> {
                // Proceed as soon as N branches succeed (or timeout). Cancel the rest; persist all with cancelled placeholders.
                int target = Math.min(waitForCount, branchNames.size());
                ConcurrentHashMap<String, BranchResult> resultsByBranch = new ConcurrentHashMap<>();
                AtomicInteger successCount = new AtomicInteger(0);
                CompletableFuture<Void> quorumReached = new CompletableFuture<>();

                for (int i = 0; i < futures.size(); i++) {
                    final int idx = i;
                    String branchName = branchNames.get(idx);
                    CompletableFuture<BranchResult> f = futures.get(idx);
                    f.whenComplete((result, ex) -> {
                        BranchResult r;
                        if (result != null) {
                            r = result;
                        } else if (ex instanceof CancellationException) {
                            r = BranchResult.cancelled(branchName);
                        } else {
                            r = BranchResult.failure(branchName, ex != null && ex.getMessage() != null ? ex.getMessage() : "unknown", 0);
                        }
                        resultsByBranch.put(branchName, r);
                        if (r.isSuccess() && successCount.incrementAndGet() >= target) {
                            quorumReached.complete(null);
                        }
                    });
                }

                try {
                    quorumReached.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    if (!continuePartial) {
                        throw new RuntimeException(
                                "Fork WAIT_N timed out after " + timeoutSeconds + "s waiting for " + target + " branches to succeed");
                    }
                    log.warn("[ForkNode] WAIT_N timeout after {}s — collecting completed futures", timeoutSeconds);
                }
                cancelAllFutures(futures);
                // Wait briefly for cancelled futures to finish so we can collect their final state
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // use whatever we have in resultsByBranch
                }
                // Build list in branch order; use CANCELLED for any branch not yet in map
                List<BranchResult> results = new ArrayList<>();
                for (String name : branchNames) {
                    results.add(resultsByBranch.getOrDefault(name, BranchResult.cancelled(name)));
                }
                return results;
            }
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }
    }

    private void cancelAllFutures(List<CompletableFuture<BranchResult>> futures) {
        futures.stream()
                .filter(f -> !f.isDone())
                .forEach(f -> f.cancel(true));
    }

    private List<BranchResult> collectWithTimeout(
            List<CompletableFuture<BranchResult>> futures,
            List<String> names) {
        List<BranchResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<BranchResult> f = futures.get(i);
            if (f.isDone() && !f.isCancelled()) {
                results.add(f.join());
            } else {
                f.cancel(true);
                results.add(BranchResult.timeout(names.get(i), DEFAULT_TIMEOUT_SECONDS * 1000L));
            }
        }
        return results;
    }

    /**
     * Resolves the ordered node list for one branch from the FORK node config.
     *
     * Expected config shape on the FORK node:
     * {
     *   "branches": ["branchA", "branchB"],
     *   "branchNodeIds": {
     *     "branchA": ["uuid-of-script-node", "uuid-of-http-node"],
     *     "branchB": ["uuid-of-ai-node"]
     *   }
     * }
     *
     * IMPORTANT: branchNodeIds must NEVER include the FORK node's own ID or any JOIN node ID.
     * Returns an empty list if branchNodeIds is not configured so branches complete
     * immediately with empty nex rather than deadlocking.
     */
    @SuppressWarnings("unchecked")
    private List<FlowNode> resolveBranchNodes(
            FlowNode forkNode,
            String branchName,
            Map<UUID, FlowNode> nodeById) {

        Map<String, List<String>> branchNodeIds =
                (Map<String, List<String>>) forkNode.getConfig().get("branchNodeIds");

        if (branchNodeIds == null || !branchNodeIds.containsKey(branchName)) {
            log.warn("[ForkNode] No branchNodeIds configured for branch '{}'. " +
                            "Connect nodes to this branch via the Studio canvas.",
                    branchName);
            return Collections.emptyList();
        }

        List<String> ids = branchNodeIds.get(branchName);

        return ids.stream()
                .map(idStr -> {
                    try {
                        UUID nodeId = UUID.fromString(idStr);
                        FlowNode node = nodeById.get(nodeId);

                        if (node == null) {
                            log.warn("[ForkNode] branchNodeId '{}' not found in flow graph for branch '{}'",
                                    idStr, branchName);
                            return null;
                        }

                        if (node.getNodeType() == NodeType.FORK || node.getNodeType() == NodeType.JOIN) {
                            log.error("[ForkNode] branchNodeId '{}' is a {} node — FORK/JOIN cannot be branch nodes. Skipping.",
                                    idStr, node.getNodeType());
                            return null;
                        }

                        return node;

                    } catch (IllegalArgumentException e) {
                        log.warn("[ForkNode] Invalid UUID '{}' in branchNodeIds for branch '{}'",
                                idStr, branchName);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private String extractBranchName(
            CompletableFuture<BranchResult> f,
            List<String> names,
            List<CompletableFuture<BranchResult>> futures) {
        int idx = futures.indexOf(f);
        return idx >= 0 && idx < names.size() ? names.get(idx) : "unknown";
    }

    private String getString(Map<String, Object> cfg, String key, String def) {
        Object v = cfg.get(key);
        return v instanceof String s ? s : def;
    }

    private int getInt(Map<String, Object> cfg, String key, int def) {
        Object v = cfg.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    /** Normalise config values so frontend can send "fail_fast", "continue", "wait-all" etc. */
    private static String normalise(String value) {
        if (value == null || value.isBlank()) return value;
        return value.toUpperCase().replace("-", "_").replace(" ", "_").trim();
    }

    private NodeContext failure(FlowNode node, String message) {
        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(NodeType.FORK.name())
                .status(NodeStatus.FAILURE)
                .errorMessage(message)
                .build();
    }
}

