package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.repository.ExecutionRepository;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final ExecutionRepository executionRepository;
    private final FlowRepository      flowRepository;

    // GET /api/executions — full history across all flows, newest first
    @GetMapping
    public List<ExecutionSummary> listAll() {

        List<Execution> executions = executionRepository.findAllByOrderByStartedAtDesc();

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
    public ResponseEntity<ExecutionDetail> getById(@PathVariable UUID id) {
        return executionRepository.findById(id)
                .map(e -> {
                    String flowName = flowRepository.findById(e.getFlowId())
                            .map(Flow::getName).orElse("Unknown");
                    String flowSlug = flowRepository.findById(e.getFlowId())
                            .map(Flow::getSlug).orElse("");
                    return ResponseEntity.ok(toDetail(e, flowName, flowSlug));
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
    

    private ExecutionDetail toDetail(Execution e, String flowName, String flowSlug) {
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
                e.getNcoSnapshot()  // full NCO — contains all node inputs/outputs
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

    public record ExecutionDetail(
            String              id,
            String              flowId,
            String              flowName,
            String              flowSlug,
            String              status,
            String              triggeredBy,
            String              startedAt,
            String              completedAt,
            long                durationMs,
            Map<String, Object> ncoSnapshot  // raw NCO for full node I/O inspection
    ) {}
}
