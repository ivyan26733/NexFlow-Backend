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
     * Triggers a flow and returns immediately with the execution (status RUNNING).
     * The actual execution runs in the background so the frontend can subscribe to
     * WebSocket events before they are sent. Used by Pulse API (Studio Trigger).
     */
    public Execution triggerFlow(UUID flowId, Map<String, Object> payload, String triggeredBy) {
        flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));

        Execution execution = new Execution();
        execution.setFlowId(flowId);
        execution.setTriggeredBy(triggeredBy);
        execution = executionRepository.save(execution);
        final UUID executionId = execution.getId();
        log.info("[WS-DEBUG] triggerFlow: returning executionId={} immediately (client should subscribe to /topic/execution/{})", executionId, executionId);
        CompletableFuture.runAsync(() -> runExecutionInBackground(executionId, flowId, payload));
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
        execution = executionRepository.save(execution);

        final UUID executionId = execution.getId();
        runExecutionInBackground(executionId, flowId, payload);
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException("Execution not found after run: " + executionId));
    }

    private void runExecutionInBackground(UUID executionId, UUID flowId, Map<String, Object> payload) {
        log.info("[WS-DEBUG] runExecutionInBackground: starting executionId={}, flowId={}", executionId, flowId);
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException("Execution not found: " + executionId));
        try {
            NexflowContextObject nco = engine.execute(flowId, executionId.toString(), payload);
            execution.setStatus(nco.getMeta().getStatus());
            execution.setNcoSnapshot(objectMapper.convertValue(nco, Map.class));
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.error("Flow {} execution failed: {}", flowId, msg, ex);
            execution.setStatus(ExecutionStatus.FAILURE);
        }
        execution.setCompletedAt(Instant.now());
        executionRepository.save(execution);
        log.info("[WS-DEBUG] runExecutionInBackground: finished executionId={}, status={}", executionId, execution.getStatus());
    }

    
    
    /**
     * Convenience overload — keeps PulseController call-site clean.
     */
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
