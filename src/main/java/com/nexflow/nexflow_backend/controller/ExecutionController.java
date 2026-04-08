package com.nexflow.nexflow_backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.model.domain.*;
import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import com.nexflow.nexflow_backend.repository.BranchExecutionRepository;
import com.nexflow.nexflow_backend.repository.ExecutionRepository;
import com.nexflow.nexflow_backend.repository.NodeExecutionRepository;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import com.nexflow.nexflow_backend.service.ExecutionListCacheService;
import com.nexflow.nexflow_backend.service.FlowService;
import com.nexflow.nexflow_backend.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
@Slf4j
public class ExecutionController {

    private static final Duration RECENT_WINDOW = Duration.ofDays(2);

    private final ExecutionRepository executionRepository;
    private final FlowRepository      flowRepository;
    private final BranchExecutionRepository branchExecutionRepository;
    private final NodeExecutionRepository nodeExecutionRepository;
    private final FlowService         flowService;
    private final GroupService        groupService;
    private final ExecutionListCacheService executionListCacheService;
    private final ObjectMapper        objectMapper;

    /**
     * Aggregate counts for stat cards (always from DB, all accessible executions).
     */
    @GetMapping("/stats")
    public ExecutionStats stats(@AuthenticationPrincipal NexUser user) {
        if (user == null) {
            return new ExecutionStats(0, 0, 0, 0);
        }
        ExecutionStats out;
        if (user.getRole() == UserRole.ADMIN) {
            out = executionStatsFromRows(executionRepository.countByStatusAll());
        } else {
            List<UUID> flowIds = getAccessibleFlowIds(user);
            if (flowIds.isEmpty()) {
                out = new ExecutionStats(0, 0, 0, 0);
            } else {
                out = executionStatsFromRows(executionRepository.countByStatusForFlowIds(flowIds));
            }
        }
        log.info("[Execution] /stats userId={} role={} total={} success={} failure={} running={}",
                user.getId(), user.getRole(), out.total(), out.success(), out.failure(), out.running());
        return out;
    }

    /**
     * GET /api/executions — default: rolling 2-day window (Redis when valid); fullHistory=true: all from DB.
     */
    @GetMapping
    public List<ExecutionSummary> listAll(
            @AuthenticationPrincipal NexUser user,
            @RequestParam(defaultValue = "false") boolean fullHistory) {
        if (user == null) {
            return List.of();
        }
        if (fullHistory) {
            List<ExecutionSummary> full = listAllFromDatabase(user);
            log.info("[Execution] list fullHistory=true userId={} size={} (source=DB)", user.getId(), full.size());
            return full;
        }

        if (!executionListCacheService.redisAvailable()) {
            log.info("[Execution] list recent — Redis unavailable; loading 2d window from DB userId={}", user.getId());
            List<ExecutionSummary> summaries = toSummaries(loadWindowedExecutions(user));
            log.info("[Execution] list recent from DB userId={} size={} (Redis skipped)", user.getId(), summaries.size());
            return summaries;
        }

        long gen = executionListCacheService.readGeneration();
        if (gen < 0) {
            log.info("[Execution] list recent — generation unreadable; loading from DB userId={}", user.getId());
            List<ExecutionSummary> summaries = toSummaries(loadWindowedExecutions(user));
            log.info("[Execution] list recent from DB userId={} size={}", user.getId(), summaries.size());
            return summaries;
        }

        Optional<String> raw = executionListCacheService.getPayloadJson(user.getId().toString());
        if (raw.isEmpty()) {
            log.info("[Execution] list recent — Redis cache miss (no entry) userId={} currentGen={}", user.getId(), gen);
        } else {
            try {
                JsonNode root = objectMapper.readTree(raw.get());
                long cachedGen = root.has("generation") ? root.get("generation").asLong(-1) : -1;
                if (cachedGen != gen) {
                    log.info("[Execution] list recent — cache stale userId={} cachedGen={} currentGen={} (will reload from DB)",
                            user.getId(), cachedGen, gen);
                } else if (root.has("summaries")) {
                    List<ExecutionSummary> cached = objectMapper.convertValue(
                            root.get("summaries"),
                            new TypeReference<List<ExecutionSummary>>() {});
                    log.info("[Execution] list recent from Redis userId={} generation={} size={}",
                            user.getId(), gen, cached.size());
                    return cached;
                } else {
                    log.warn("[Execution] Redis payload missing summaries userId={} generation={}", user.getId(), gen);
                }
            } catch (Exception ex) {
                log.warn("[Execution] Redis recent list parse failed userId={}: {}", user.getId(), ex.getMessage());
            }
        }

        List<Execution> windowed = loadWindowedExecutions(user);
        List<ExecutionSummary> summaries = toSummaries(windowed);

        try {
            var node = objectMapper.createObjectNode();
            node.put("generation", gen);
            node.set("summaries", objectMapper.valueToTree(summaries));
            executionListCacheService.putPayloadJson(user.getId().toString(), objectMapper.writeValueAsString(node));
        } catch (Exception ex) {
            log.warn("[Execution] failed to store recent list in Redis: {}", ex.getMessage());
        }
        log.info("[Execution] list recent from DB userId={} generation={} size={} (stored to Redis)",
                user.getId(), gen, summaries.size());
        return summaries;
    }

    private List<ExecutionSummary> listAllFromDatabase(NexUser user) {
        List<Execution> executions;
        if (user.getRole() == UserRole.ADMIN) {
            executions = executionRepository.findAllByOrderByStartedAtDesc();
        } else {
            List<UUID> accessibleFlowIds = getAccessibleFlowIds(user);
            executions = executionRepository.findByFlowIdInOrderByStartedAtDesc(accessibleFlowIds);
        }
        return toSummaries(executions);
    }

    /**
     * Last 2 days from now; if empty, last 2 days ending at the newest {@code startedAt} in scope (anchor window).
     */
    private List<Execution> loadWindowedExecutions(NexUser user) {
        Instant now = Instant.now();
        Instant since = now.minus(RECENT_WINDOW);
        if (user.getRole() == UserRole.ADMIN) {
            List<Execution> execs = executionRepository.findByStartedAtAfterOrderByStartedAtDesc(since);
            if (!execs.isEmpty()) {
                log.info("[Execution] window=ROLLING_2D admin=true since={} count={}", since, execs.size());
                return execs;
            }
            Optional<Instant> maxOpt = executionRepository.findMaxStartedAt();
            if (maxOpt.isEmpty()) {
                log.info("[Execution] window=EMPTY admin=true (no executions in DB)");
                return List.of();
            }
            Instant anchor = maxOpt.get();
            List<Execution> anchored = executionRepository.findByStartedAtBetweenOrderByStartedAtDesc(
                    anchor.minus(RECENT_WINDOW), anchor);
            log.info("[Execution] window=ANCHOR admin=true anchor={} count={} (no rows in last 2d)", anchor, anchored.size());
            return anchored;
        }
        List<UUID> flowIds = getAccessibleFlowIds(user);
        if (flowIds.isEmpty()) {
            log.info("[Execution] window=EMPTY userId={} (no accessible flows)", user.getId());
            return List.of();
        }
        List<Execution> execs = executionRepository.findByFlowIdInAndStartedAtAfterOrderByStartedAtDesc(flowIds, since);
        if (!execs.isEmpty()) {
            log.info("[Execution] window=ROLLING_2D userId={} since={} count={}", user.getId(), since, execs.size());
            return execs;
        }
        Optional<Instant> maxOpt = executionRepository.findMaxStartedAtByFlowIdIn(flowIds);
        if (maxOpt.isEmpty()) {
            log.info("[Execution] window=EMPTY userId={} (no executions for accessible flows)", user.getId());
            return List.of();
        }
        Instant anchor = maxOpt.get();
        List<Execution> anchored = executionRepository.findByFlowIdInAndStartedAtBetweenOrderByStartedAtDesc(
                flowIds, anchor.minus(RECENT_WINDOW), anchor);
        log.info("[Execution] window=ANCHOR userId={} anchor={} count={} (no rows in last 2d)", user.getId(), anchor, anchored.size());
        return anchored;
    }

    private List<ExecutionSummary> toSummaries(List<Execution> executions) {
        List<UUID> flowIds = executions.stream()
                .map(Execution::getFlowId)
                .distinct()
                .toList();
        Map<UUID, Flow> flowsById = flowRepository.findAllById(flowIds).stream()
                .collect(Collectors.toMap(Flow::getId, f -> f));
        return executions.stream()
                .map(e -> toSummary(e, flowsById))
                .toList();
    }

    private static ExecutionStats executionStatsFromRows(List<Object[]> rows) {
        long total = 0;
        long success = 0;
        long failure = 0;
        long running = 0;
        if (rows == null) {
            return new ExecutionStats(0, 0, 0, 0);
        }
        for (Object[] row : rows) {
            if (row == null || row.length < 2) continue;
            ExecutionStatus st = (ExecutionStatus) row[0];
            long c = ((Number) row[1]).longValue();
            total += c;
            if (st == ExecutionStatus.SUCCESS) success += c;
            else if (st == ExecutionStatus.FAILURE || st == ExecutionStatus.TIMEOUT) failure += c;
            else if (st == ExecutionStatus.RUNNING) running += c;
        }
        return new ExecutionStats(total, success, failure, running);
    }

    public record ExecutionStats(long total, long success, long failure, long running) {}
    // GET /api/executions/{id} — full detail including NCO snapshot (all node I/O)
    @GetMapping("/{id}")
    public ResponseEntity<ExecutionDetail> getById(@PathVariable UUID id,
                                                    @AuthenticationPrincipal NexUser user) {
        return executionRepository.findById(id)
                .filter(e -> canAccessExecution(e, user))
                .map(e -> {
                    String flowName = flowRepository.findById(e.getFlowId())
                            .map(Flow::getName).orElse("Unknown");
                    String flowSlug = flowRepository.findById(e.getFlowId())
                            .map(Flow::getSlug).orElse("");
                    List<BranchExecution> branches = branchExecutionRepository.findByExecutionId(id);
                    List<NodeExecution> nodeExecutions = nodeExecutionRepository.findByExecutionIdOrderByStartedAtAsc(id);
                    return ResponseEntity.ok(toDetail(e, flowName, flowSlug, branches, nodeExecutions));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // GET /api/executions/{id}/nex — convenience: only the nex map from the transaction snapshot
    @GetMapping("/{id}/nex")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> getNex(@PathVariable UUID id) {
        return executionRepository.findById(id)
                .map(exec -> {
                    Map<String, Object> nco = exec.getNcoSnapshot();
                    Object nex = nco != null ? nco.get("nex") : null;
                    Map<String, Object> nexMap = nex instanceof Map ? (Map<String, Object>) nex : Map.of();
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "transactionId", exec.getId().toString(),
                            "nex", nexMap
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/executions/recent?flowId={id}&hours=1
     *
     * Returns up to 10 recent executions for a flow within the last N hours,
     * each with per-node execution summaries. Used by the AI Assistant to diagnose failures.
     */
    @GetMapping("/recent")
    public List<TransactionDigest> getRecent(
            @RequestParam UUID flowId,
            @RequestParam(defaultValue = "1") int hours) {

        Instant since = Instant.now().minusSeconds((long) hours * 3600);
        List<Execution> executions = executionRepository
                .findByFlowIdAndStartedAtAfterOrderByStartedAtDesc(flowId, since)
                .stream().limit(10).toList();

        return executions.stream().map(e -> {
            List<NodeExecution> nodeExecs = nodeExecutionRepository
                    .findByExecutionIdOrderByStartedAtAsc(e.getId());
            List<NodeDigest> nodes = nodeExecs.stream().map(n -> new NodeDigest(
                    n.getNodeLabel(),
                    n.getNodeType(),
                    n.getStatus().name(),
                    n.getBranchName(),
                    n.getErrorMessage(),
                    n.getDurationMs() != null ? n.getDurationMs() : -1L
            )).toList();

            long durationMs = (e.getCompletedAt() != null && e.getStartedAt() != null)
                    ? Duration.between(e.getStartedAt(), e.getCompletedAt()).toMillis() : -1;

            return new TransactionDigest(
                    e.getId().toString(),
                    e.getStatus().name(),
                    e.getStartedAt() != null ? e.getStartedAt().toString() : null,
                    e.getCompletedAt() != null ? e.getCompletedAt().toString() : null,
                    durationMs,
                    e.getErrorMessage(),
                    nodes
            );
        }).toList();
    }

    /**
     * POST /api/executions/{executionId}/start
     *
     * Called by the frontend AFTER it has confirmed its STOMP subscription
     * to /queue/execution.{executionId}.
     *
     * Returns 202 Accepted immediately. Execution runs in background.
     */
    @PostMapping("/{executionId}/start")
    public ResponseEntity<Void> startExecution(@PathVariable UUID executionId) {
        log.info("[ExecutionController] start requested for executionId={}", executionId);
        flowService.startExecution(executionId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/discard-running")
    public ResponseEntity<Map<String, Integer>> discardRunning(@AuthenticationPrincipal NexUser user) {
        if (user == null) return ResponseEntity.status(401).build();
        Set<UUID> allowedFlowIds = null;
        if (user.getRole() != UserRole.ADMIN) {
            allowedFlowIds = new HashSet<>(getAccessibleFlowIds(user));
        }
        int discarded = flowService.discardRunningExecutions(allowedFlowIds);
        log.info("[Execution] discard-running userId={} role={} discarded={}",
                user.getId(), user.getRole(), discarded);
        return ResponseEntity.ok(Map.of("discarded", discarded));
    }

    // ── Access helpers ────────────────────────────────────────────────────────

    private List<UUID> getAccessibleFlowIds(NexUser user) {
        if (user == null) return List.of();
        return flowRepository.findAll().stream()
                .filter(f -> groupService.hasFlowAccess(f.getId(), f.getUserId(), user))
                .map(Flow::getId)
                .toList();
    }

    private boolean canAccessExecution(Execution e, NexUser user) {
        if (user == null) return false;
        if (user.getRole() == UserRole.ADMIN) return true;
        Flow flow = flowRepository.findById(e.getFlowId()).orElse(null);
        if (flow == null) return false;
        return groupService.hasFlowAccess(flow.getId(), flow.getUserId(), user);
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    private ExecutionSummary toSummary(Execution e, Map<UUID, Flow> flowsById) {
        Flow flow = flowsById.get(e.getFlowId());
    
        String flowName = flow != null && flow.getName() != null
                ? flow.getName()
                : "Unknown";
    
        String flowSlug = flow != null && flow.getSlug() != null
                ? flow.getSlug()
                : "";
    
        long durationMs = (e.getCompletedAt() != null && e.getStartedAt() != null)
                ? Duration.between(e.getStartedAt(), e.getCompletedAt()).toMillis()
                : -1;
    
        return new ExecutionSummary(
                e.getId().toString(),
                e.getFlowId().toString(),
                flowName,
                flowSlug,
                e.getStatus().name(),
                e.getTriggeredBy(),
                e.getStartedAt() != null ? e.getStartedAt().toString() : null,
                e.getCompletedAt() != null ? e.getCompletedAt().toString() : null,
                durationMs
        );
    }
    

    private ExecutionDetail toDetail(Execution e, String flowName, String flowSlug,
                                     List<BranchExecution> branches, List<NodeExecution> nodeExecutions) {
        long durationMs = (e.getCompletedAt() != null && e.getStartedAt() != null)
                ? Duration.between(e.getStartedAt(), e.getCompletedAt()).toMillis()
                : -1;

        return new ExecutionDetail(
                e.getId().toString(),
                e.getFlowId().toString(),
                flowName,
                flowSlug,
                e.getStatus().name(),
                e.getTriggeredBy(),
                e.getStartedAt() != null ? e.getStartedAt().toString() : null,
                e.getCompletedAt() != null ? e.getCompletedAt().toString() : null,
                durationMs,
                e.getNcoSnapshot(),  // full NCO — contains all node inputs/outputs
                branches != null ? branches : List.of(),
                nodeExecutions != null ? nodeExecutions : List.of()
        );
    }

    public record ExecutionSummary(
            String id,
            String flowId,
            String flowName,
            String flowSlug,
            String status,
            String triggeredBy,
            String startedAt,
            String completedAt,
            long   durationMs
    ) {}

    public record NodeDigest(
            String label,
            String type,
            String status,
            String branchName,
            String errorMessage,
            long   durationMs
    ) {}

    public record TransactionDigest(
            String           id,
            String           status,
            String           startedAt,
            String           completedAt,
            long             durationMs,
            String           errorMessage,
            List<NodeDigest> nodes
    ) {}

    public record ExecutionDetail(
            String                id,
            String                flowId,
            String                flowName,
            String                flowSlug,
            String                status,
            String                triggeredBy,
            String                startedAt,
            String                completedAt,
            long                  durationMs,
            Map<String, Object>   ncoSnapshot,   // raw NCO for full node I/O inspection
            List<BranchExecution> branches,      // per-fork branch execution records for transaction UI
            List<NodeExecution>   nodeExecutions // branch node runs with inputNex/outputNex for request/response
    ) {}
}
