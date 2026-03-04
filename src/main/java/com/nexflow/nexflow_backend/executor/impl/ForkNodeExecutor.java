package com.nexflow.nexflow_backend.executor.impl;

import com.nexflow.nexflow_backend.engine.ExecutionEventPublisher;
import com.nexflow.nexflow_backend.engine.FlowExecutionEngine;
import com.nexflow.nexflow_backend.executor.NodeExecutor;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final ExecutionEventPublisher eventPublisher;
    @Qualifier("flowExecutionExecutor")
    private final Executor flowExecutionExecutor;

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

        int timeoutSeconds = getInt(cfg, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        String strategyStr = getString(cfg, "strategy", "WAIT_ALL");
        int waitForCount = getInt(cfg, "waitForCount", branchNames.size());
        boolean failFast = getString(cfg, "onBranchFailure", "FAIL_FAST").equals("FAIL_FAST");
        boolean continuePartial = getString(cfg, "onTimeout", "FAIL").equals("CONTINUE_WITH_PARTIAL");

        JoinStrategy strategy;
        try {
            strategy = JoinStrategy.valueOf(strategyStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[ForkNode] Unknown strategy '{}', defaulting to WAIT_ALL", strategyStr);
            strategy = JoinStrategy.WAIT_ALL;
        }

        String executionId = nco.getMeta().getExecutionId();
        UUID forkNodeId = node.getId();

        log.info("[ForkNode] '{}' starting fork — branches={} strategy={} timeout={}s",
                node.getLabel(), branchNames, strategy, timeoutSeconds);

        eventPublisher.nodeStarted(executionId, node.getId().toString());

        // One DB row per branch so we can inspect later in Transactions UI
        List<BranchExecution> dbBranches = branchNames.stream()
                .map(name -> createBranchRecord(executionId, forkNodeId, name))
                .collect(Collectors.toList());

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
        }

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
                        flowExecutionExecutor
                ))
                .toList();

        List<BranchResult> results;
        try {
            results = applyStrategy(strategy, futures, waitForCount, timeoutSeconds, continuePartial, branchNames);
        } catch (Exception e) {
            log.error("[ForkNode] '{}' strategy execution failed: {}", node.getLabel(), e.getMessage());
            cancelAllFutures(futures);
            eventPublisher.nodeError(executionId, node.getId().toString(), e.getMessage());
            return failure(node, e.getMessage());
        }

        long totalParallelMs = System.currentTimeMillis() - forkStartMs;

        List<BranchResult> failures = results.stream()
                .filter(r -> r.isFailed() || r.isTimeout())
                .toList();

        if (!failures.isEmpty() && failFast) {
            String errorSummary = failures.stream()
                    .map(r -> r.getBranchName() + ": " + r.getErrorMessage())
                    .collect(Collectors.joining(", "));
            log.error("[ForkNode] '{}' failed branches: {}", node.getLabel(), errorSummary);
            eventPublisher.nodeError(executionId, node.getId().toString(), errorSummary);
            updateBranchRecords(dbBranches, results);
            return failure(node, errorSummary);
        }

        // Merge successful branch outputs into parent nex
        results.stream()
                .filter(BranchResult::isSuccess)
                .forEach(r -> nco.getNex().put(r.getBranchName(), r.getNex()));

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

        updateBranchRecords(dbBranches, results);

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

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("[ForkNode] Branch '{}' FAILED after {}ms: {}", branchName, durationMs, e.getMessage(), e);
            eventPublisher.branchCompleted(executionId, forkNodeId.toString(), branchName, NodeStatus.FAILURE, durationMs, e.getMessage());
            return BranchResult.failure(branchName, e.getMessage(), durationMs);
        }
    }

    private List<BranchResult> applyStrategy(
            JoinStrategy strategy,
            List<CompletableFuture<BranchResult>> futures,
            int waitForCount,
            int timeoutSeconds,
            boolean continuePartial,
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
                CompletableFuture<Object> anyDone = CompletableFuture.anyOf(
                        futures.toArray(new CompletableFuture[0]));
                anyDone.get(timeoutSeconds, TimeUnit.SECONDS);
                cancelAllFutures(futures);
                return futures.stream()
                        .map(f -> f.isDone() ? f.join()
                                : BranchResult.cancelled(extractBranchName(f, branchNames, futures)))
                        .toList();
            }
            case WAIT_N -> {
                int target = Math.min(waitForCount, futures.size());
                List<BranchResult> collected = new ArrayList<>();
                CountDownLatch latch = new CountDownLatch(target);
                futures.forEach(f -> f.whenComplete((result, ex) -> {
                    synchronized (collected) {
                        if (collected.size() < target) {
                            collected.add(result != null ? result
                                    : BranchResult.failure("unknown", ex != null ? ex.getMessage() : "unknown", 0));
                            latch.countDown();
                        }
                    }
                }));
                boolean reached = latch.await(timeoutSeconds, TimeUnit.SECONDS);
                if (!reached && !continuePartial) {
                    throw new RuntimeException(
                            "Fork timed out waiting for " + target + " branches to complete");
                }
                cancelAllFutures(futures);
                return collected;
            }
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }
    }

    private BranchExecution createBranchRecord(String executionId, UUID forkNodeId, String branchName) {
        BranchExecution b = new BranchExecution();
        b.setExecutionId(UUID.fromString(executionId));
        b.setForkNodeId(forkNodeId);
        b.setBranchName(branchName);
        b.setStatus(BranchExecutionStatus.PENDING);
        return branchRepo.save(b);
    }

    private void updateBranchRecords(List<BranchExecution> dbBranches, List<BranchResult> results) {
        Map<String, BranchResult> byName = results.stream()
                .collect(Collectors.toMap(BranchResult::getBranchName, r -> r));

        dbBranches.forEach(db -> {
            BranchResult r = byName.get(db.getBranchName());
            if (r == null) {
                return;
            }
            db.setStatus(r.getStatus());
            db.setDurationMs(r.getDurationMs());
            db.setCompletedAt(LocalDateTime.now());
            if (r.isSuccess()) {
                db.setNexSnapshot(r.getNex());
            }
            if (r.getErrorMessage() != null) {
                db.setErrorMessage(r.getErrorMessage());
            }
            branchRepo.save(db);
        });
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

    private NodeContext failure(FlowNode node, String message) {
        return NodeContext.builder()
                .nodeId(node.getId().toString())
                .nodeType(NodeType.FORK.name())
                .status(NodeStatus.FAILURE)
                .errorMessage(message)
                .build();
    }
}

