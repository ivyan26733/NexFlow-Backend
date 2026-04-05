package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.*;
import com.nexflow.nexflow_backend.repository.BranchExecutionRepository;
import com.nexflow.nexflow_backend.repository.ExecutionRepository;
import com.nexflow.nexflow_backend.repository.NodeExecutionRepository;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import com.nexflow.nexflow_backend.service.FlowService;
import com.nexflow.nexflow_backend.service.GroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
@Slf4j
public class ExecutionController {

    private final ExecutionRepository executionRepository;
    private final FlowRepository      flowRepository;
    private final BranchExecutionRepository branchExecutionRepository;
    private final NodeExecutionRepository nodeExecutionRepository;
    private final FlowService         flowService;
    private final GroupService        groupService;

    // GET /api/executions — full history, scoped to the requesting user's accessible flows
    @GetMapping
    public List<ExecutionSummary> listAll(@AuthenticationPrincipal NexUser user) {
        List<Execution> executions;
        if (user != null && user.getRole() == UserRole.ADMIN) {
            executions = executionRepository.findAllByOrderByStartedAtDesc();
        } else {
            List<UUID> accessibleFlowIds = getAccessibleFlowIds(user);
            executions = executionRepository.findByFlowIdInOrderByStartedAtDesc(accessibleFlowIds);
        }

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
