package com.nexflow.nexflow_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.nexflow.nexflow_backend.engine.FlowExecutionEngine;
import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.repository.ExecutionRepository;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlowService {

    private final FlowExecutionEngine engine;
    private final FlowRepository flowRepository;
    private final ExecutionRepository executionRepository;
    private final ExecutionListCacheService executionListCacheService;
    private final ObjectMapper objectMapper;
    @Qualifier("flowExecutionExecutor")
    private final Executor flowExecutionExecutor;

    /**
     * Tracks which executionIds have an active background thread.
     * Prevents the same execution from being launched twice when
     * multiple callers race to invoke startExecution().
     */
    private final Set<UUID> activeExecutions = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CompletableFuture<Void>> executionTasks = new ConcurrentHashMap<>();
    private static final String DISCARDED_ERROR_MESSAGE = "Execution discarded by user.";

    /**
     * Phase 1: Create the execution record and persist the trigger payload.
     * Does NOT start the flow engine. The caller must invoke startExecution()
     * when it is ready for the execution to begin.
     *
     * NOTE: For backward compatibility with the existing database constraint on
     * executions.status, we keep the initial status as RUNNING. The actual
     * engine work will still be deferred until startExecution is called.
     */
    public Execution prepareExecution(UUID flowId, Map<String, Object> payload, String triggeredBy) {
        flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));

        Execution execution = new Execution();
        execution.setFlowId(flowId);
        execution.setTriggeredBy(triggeredBy);
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setPayload(payload);
        execution.setCompletedAt(null);
        execution = executionRepository.save(execution);
        executionListCacheService.bumpGeneration();

        log.info(
                "[FlowService] prepareExecution flowId={} executionId={} triggeredBy={} payloadKeys={}",
                flowId,
                execution.getId(),
                triggeredBy,
                payload != null ? payload.keySet() : "null"
        );

        // Store the original payload in the NCO snapshot later; for now, pass it directly
        // to the engine when the execution actually starts.

        // We don't persist the payload separately to avoid schema changes in production.

        return execution;
    }

    /**
     * Phase 2: Start a previously prepared execution asynchronously.
     * Used by Studio after the WebSocket subscription is ready.
     */
    public void startExecution(UUID executionId) {
        startExecution(executionId, Map.of());
    }

    /**
     * Phase 2: Start a previously prepared execution asynchronously.
     * Used by Studio after the WebSocket subscription is ready.
     */
    public void startExecution(UUID executionId, Map<String, Object> payload) {
        logPoolStats();

        // Guard against double-start: if a background task is already running for this executionId,
        // skip quietly. This handles races between different controllers calling startExecution().
        if (!activeExecutions.add(executionId)) {
            log.warn("[FlowService] startExecution DUPLICATE — executionId={} already submitted, skipping", executionId);
            return;
        }

        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));

        log.info(
                "[FlowService] startExecution executionId={} flowId={}",
                executionId,
                execution.getFlowId()
        );

        Map<String, Object> effectivePayload = execution.getPayload() != null
                ? execution.getPayload()
                : (payload != null ? payload : Map.of());

        // The execution row is already saved. The actual flow work happens on the background executor.
        CompletableFuture<Void> task = CompletableFuture.runAsync(
                () -> runExecutionInBackground(executionId, execution.getFlowId(), effectivePayload),
                flowExecutionExecutor
        );
        executionTasks.put(executionId, task);
        task.whenComplete((ok, err) -> {
            activeExecutions.remove(executionId);
            executionTasks.remove(executionId);
        });
    }

    /**
     * Single-phase execution: prepare + start in one call.
     * Used by external callers (webhooks, API, JMeter, scheduled pulses)
     * that do not have a browser WebSocket waiting to subscribe.
     */
    public Execution prepareAndStartExecution(UUID flowId,
                                              Map<String, Object> payload,
                                              String triggeredBy) {
        Execution execution = prepareExecution(flowId, payload, triggeredBy);
        startExecution(execution.getId(), payload);
        log.info("[FlowService] prepareAndStartExecution complete flowId={} executionId={} triggeredBy={}",
                flowId, execution.getId(), triggeredBy);
        return execution;
    }

    /**
     * Triggers a flow and blocks until complete. Returns the final execution.
     * Used by SubFlowExecutor in SYNC mode.
     */
    public Execution triggerFlowSync(UUID flowId, Map<String, Object> payload, String triggeredBy) {
        flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));

        Execution execution = new Execution();
        execution.setFlowId(flowId);
        execution.setTriggeredBy(triggeredBy);
        execution.setPayload(payload);
        execution = executionRepository.save(execution);
        executionListCacheService.bumpGeneration();

        final UUID executionId = execution.getId();

        log.info(
                "[FlowService] triggerFlowSync flowId={} executionId={} triggeredBy={} payloadKeys={}",
                flowId,
                executionId,
                triggeredBy,
                payload != null ? payload.keySet() : "null"
        );

        runExecutionInBackground(executionId, flowId, payload);
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException("Execution not found after run: " + executionId));
    }

    private void runExecutionInBackground(UUID executionId, UUID flowId, Map<String, Object> payload) {
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException("Execution not found: " + executionId));
        try {
            // This is the real flow run. If it throws, we mark the execution as failed.
            log.info(
                    "[FlowService] runExecutionInBackground START flowId={} executionId={} triggeredBy={} payloadKeys={}",
                    flowId,
                    executionId,
                    execution.getTriggeredBy(),
                    payload != null ? payload.keySet() : "null"
            );

            NexflowContextObject nco = engine.execute(flowId, executionId.toString(), payload);
            Execution latest = executionRepository.findById(executionId).orElse(execution);
            if (isExternallyFinalized(latest)) {
                log.info("[FlowService] runExecutionInBackground skip finalize: executionId={} already finalized", executionId);
                return;
            }
            latest.setStatus(nco.getMeta().getStatus());
            latest.setNcoSnapshot(objectMapper.convertValue(nco, new TypeReference<Map<String, Object>>() {}));
            latest.setCompletedAt(Instant.now());
            executionRepository.save(latest);
            executionListCacheService.bumpGeneration();

            log.info(
                    "[FlowService] runExecutionInBackground END flowId={} executionId={} status={}",
                    flowId,
                    executionId,
                    nco.getMeta().getStatus()
            );
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.error("Flow {} execution failed: {}", flowId, msg, ex);
            Execution latest = executionRepository.findById(executionId).orElse(execution);
            if (isExternallyFinalized(latest)) {
                log.info("[FlowService] runExecutionInBackground skip failure finalize: executionId={} already finalized", executionId);
                return;
            }
            latest.setStatus(ExecutionStatus.FAILURE);
            latest.setErrorMessage(msg);
            // Persist snapshot so transaction is always created and detail page can show error for debugging
            Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("flowId", flowId.toString());
            meta.put("executionId", executionId.toString());
            meta.put("status", ExecutionStatus.FAILURE.name());
            meta.put("completedAt", Instant.now().toString());
            latest.setNcoSnapshot(Map.of(
                    "nodes", Map.of(),
                    "nodeExecutionOrder", List.of(),
                    "meta", meta,
                    "error", msg
            ));
            latest.setCompletedAt(Instant.now());
            executionRepository.save(latest);
            executionListCacheService.bumpGeneration();
        }
    }

    public int discardRunningExecutions(Set<UUID> allowedFlowIds) {
        List<Execution> running = executionRepository.findByStatusOrderByStartedAtDesc(ExecutionStatus.RUNNING);
        int discarded = 0;
        for (Execution execution : running) {
            if (allowedFlowIds != null && !allowedFlowIds.contains(execution.getFlowId())) {
                continue;
            }
            // This is the manual cleanup path for executions that are still stuck in RUNNING.
            execution.setStatus(ExecutionStatus.FAILURE);
            execution.setErrorMessage(DISCARDED_ERROR_MESSAGE);
            execution.setCompletedAt(Instant.now());
            executionRepository.save(execution);
            CompletableFuture<Void> task = executionTasks.remove(execution.getId());
            if (task != null) {
                task.cancel(true);
            }
            activeExecutions.remove(execution.getId());
            discarded++;
        }
        if (discarded > 0) {
            executionListCacheService.bumpGeneration();
        }
        return discarded;
    }

    private static boolean isExternallyFinalized(Execution execution) {
        return execution.getCompletedAt() != null && execution.getStatus() != ExecutionStatus.RUNNING;
    }

    
    
    /**
     * Convenience overload — keeps PulseController call-site clean.
     */
    /**
     * Convenience method for internal callers that don't need the two-phase
     * Studio pattern. Creates the execution and starts it asynchronously.
     */
    public Execution triggerFlow(UUID flowId, Map<String, Object> payload, String triggeredBy) {
        Execution execution = triggerFlowSync(flowId, payload, triggeredBy);
        return execution;
    }

    public Execution triggerFlow(UUID flowId, Map<String, Object> payload) {
        return triggerFlow(flowId, payload, "PULSE");
    }

    /**
     * ASYNCHRONOUS execution — returns immediately after persisting the Execution row.
     * The actual engine run happens on a background thread (@Async).
     * Used by SubFlowExecutor when mode = ASYNC.
     *
     * Note: @Async requires a TaskExecutor bean. Spring Boot auto-configures one when
     * @EnableAsync is present on any @Configuration class (add to AppConfig.java).
     */


    @Async
    public void triggerFlowAsync(UUID flowId, Map<String, Object> payload, String triggeredBy) {
        try {
            triggerFlowSync(flowId, payload, triggeredBy);
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.error("Async flow {} failed: {}", flowId, msg, ex);
        }
    }

    private void logPoolStats() {
        if (flowExecutionExecutor instanceof ThreadPoolTaskExecutor exec) {
            log.info("[FlowService] threadPool active={} queued={} poolSize={} maxPool={}",
                    exec.getActiveCount(),
                    exec.getThreadPoolExecutor().getQueue().size(),
                    exec.getPoolSize(),
                    exec.getMaxPoolSize());
        }
    }
}
