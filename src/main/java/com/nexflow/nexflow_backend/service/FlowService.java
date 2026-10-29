package com.nexflow.nexflow_backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.engine.FlowExecutionEngine;
import com.nexflow.nexflow_backend.engine.FriendlyErrors;
import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.repository.ExecutionRepository;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * Starts flow runs and stores their outcome.
 *
 * Every trigger (Studio "Run", public pulse webhook, schedule, sub-flow) goes through
 * {@link #start} (background) or {@link #runAndWait} (blocking, for SYNC sub-flows).
 */
@Slf4j
@Service
public class FlowService {

    private static final String DISCARDED_MESSAGE = "This run was stopped by a user.";

    private final FlowExecutionEngine engine;
    private final FlowRepository flowRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionListCacheService executionListCache;
    private final ObjectMapper objectMapper;
    private final Executor flowExecutionExecutor;

    /** Runs in progress, so a user can stop ("discard") them. */
    private final Map<UUID, Future<?>> running = new ConcurrentHashMap<>();

    public FlowService(FlowExecutionEngine engine,
                       FlowRepository flowRepository,
                       ExecutionRepository executionRepository,
                       ExecutionListCacheService executionListCache,
                       ObjectMapper objectMapper,
                       @Qualifier("flowExecutionExecutor") Executor flowExecutionExecutor) {
        this.engine = engine;
        this.flowRepository = flowRepository;
        this.executionRepository = executionRepository;
        this.executionListCache = executionListCache;
        this.objectMapper = objectMapper;
        this.flowExecutionExecutor = flowExecutionExecutor;
    }

    /** Creates the execution record and runs the flow in the background. Returns immediately. */
    public Execution start(UUID flowId, Map<String, Object> payload, String triggeredBy) {
        Flow flow = findFlow(flowId);
        Execution execution = createExecution(flow, payload, triggeredBy);
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                run(execution.getId(), flow, payload);
            } finally {
                running.remove(execution.getId());
            }
            return null;
        });
        running.put(execution.getId(), task);
        try {
            flowExecutionExecutor.execute(task);
        } catch (RuntimeException rejected) { // pool and queue full
            running.remove(execution.getId());
            finish(execution, ExecutionStatus.FAILURE, null,
                    "The server is busy running other flows. Try again in a moment.", rejected.toString());
            return execution;
        }
        log.info("[FlowService] started executionId={} flow='{}' triggeredBy={}", execution.getId(), flow.getName(), triggeredBy);
        return execution;
    }

    /** Runs the flow on the calling thread and returns the finished execution. Used by SYNC sub-flows. */
    public Execution runAndWait(UUID flowId, Map<String, Object> payload, String triggeredBy) {
        Flow flow = findFlow(flowId);
        Execution execution = createExecution(flow, payload, triggeredBy);
        run(execution.getId(), flow, payload);
        return executionRepository.findById(execution.getId()).orElse(execution);
    }

    /** Marks RUNNING executions (optionally only for the given flows) as stopped and interrupts them. */
    public int discardRunningExecutions(Set<UUID> allowedFlowIds) {
        List<Execution> stuck = executionRepository.findByStatusOrderByStartedAtDesc(ExecutionStatus.RUNNING);
        int discarded = 0;
        for (Execution execution : stuck) {
            if (allowedFlowIds != null && !allowedFlowIds.contains(execution.getFlowId())) continue;
            Future<?> task = running.remove(execution.getId());
            if (task != null) task.cancel(true);
            finish(execution, ExecutionStatus.FAILURE, null, DISCARDED_MESSAGE, null);
            discarded++;
        }
        return discarded;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void run(UUID executionId, Flow flow, Map<String, Object> payload) {
        try {
            NexflowContextObject nco = engine.execute(flow.getId(), executionId.toString(), payload, flow.getUserId());
            Execution latest = executionRepository.findById(executionId).orElseThrow();
            if (latest.getStatus() != ExecutionStatus.RUNNING) return; // discarded while running
            Map<String, Object> snapshot = objectMapper.convertValue(nco, new TypeReference<>() {});
            finish(latest, nco.getMeta().getStatus(), snapshot, nco.getMeta().getErrorMessage(), nco.getMeta().getErrorDetail());
        } catch (Exception ex) {
            log.error("Flow '{}' run {} crashed", flow.getName(), executionId, ex);
            executionRepository.findById(executionId)
                    .filter(e -> e.getStatus() == ExecutionStatus.RUNNING)
                    .ifPresent(e -> finish(e, ExecutionStatus.FAILURE, null,
                            "The run stopped unexpectedly: " + FriendlyErrors.forException(ex, null), ex.toString()));
        }
    }

    private Execution createExecution(Flow flow, Map<String, Object> payload, String triggeredBy) {
        Execution execution = new Execution();
        execution.setFlowId(flow.getId());
        execution.setTriggeredBy(triggeredBy);
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setPayload(payload != null ? payload : Map.of());
        execution = executionRepository.save(execution);
        executionListCache.bumpGeneration();
        return execution;
    }

    private void finish(Execution execution, ExecutionStatus status, Map<String, Object> snapshot,
                        String errorMessage, String errorDetail) {
        execution.setStatus(status);
        if (snapshot != null) execution.setNcoSnapshot(snapshot);
        execution.setErrorMessage(errorMessage);
        execution.setErrorDetail(errorDetail);
        execution.setCompletedAt(Instant.now());
        executionRepository.save(execution);
        executionListCache.bumpGeneration();
    }

    private Flow findFlow(UUID flowId) {
        return flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));
    }
}
