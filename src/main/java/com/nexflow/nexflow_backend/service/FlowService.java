package com.nexflow.nexflow_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.engine.FlowExecutionEngine;
import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import com.nexflow.nexflow_backend.model.nco.NexflowContextObject;
import com.nexflow.nexflow_backend.repository.ExecutionRepository;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlowService {

    private final FlowExecutionEngine engine;
    private final FlowRepository flowRepository;
    private final ExecutionRepository executionRepository;
    private final ObjectMapper objectMapper;

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
        execution.setCompletedAt(null);
        execution = executionRepository.save(execution);

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
    public void startExecution(UUID executionId, Map<String, Object> payload) {
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));

        log.info(
                "[FlowService] startExecution executionId={} flowId={}",
                executionId,
                execution.getFlowId()
        );

        CompletableFuture.runAsync(() ->
                runExecutionInBackground(executionId, execution.getFlowId(), payload)
        );
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
        execution = executionRepository.save(execution);

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
            log.info(
                    "[FlowService] runExecutionInBackground START flowId={} executionId={} triggeredBy={} payloadKeys={}",
                    flowId,
                    executionId,
                    execution.getTriggeredBy(),
                    payload != null ? payload.keySet() : "null"
            );

            NexflowContextObject nco = engine.execute(flowId, executionId.toString(), payload);
            execution.setStatus(nco.getMeta().getStatus());
            execution.setNcoSnapshot(objectMapper.convertValue(nco, Map.class));

            log.info(
                    "[FlowService] runExecutionInBackground END flowId={} executionId={} status={}",
                    flowId,
                    executionId,
                    nco.getMeta().getStatus()
            );
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.error("Flow {} execution failed: {}", flowId, msg, ex);
            execution.setStatus(ExecutionStatus.FAILURE);
            // Persist minimal snapshot so Transactions detail can show the error instead of "No node logs"
            execution.setNcoSnapshot(Map.of(
                    "nodes", Map.of(),
                    "nodeExecutionOrder", List.of(),
                    "error", msg
            ));
        }
        execution.setCompletedAt(Instant.now());
        executionRepository.save(execution);
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
}
