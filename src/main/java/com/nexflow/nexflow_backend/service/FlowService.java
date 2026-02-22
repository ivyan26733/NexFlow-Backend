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

@Slf4j
@Service
@RequiredArgsConstructor
public class FlowService {

    private final FlowExecutionEngine engine;
    private final FlowRepository flowRepository;
    private final ExecutionRepository executionRepository;
    private final ObjectMapper objectMapper;

    public Execution triggerFlow(UUID flowId, Map<String, Object> payload, String triggeredBy) {
        flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));

        Execution execution = new Execution();
        execution.setFlowId(flowId);
        execution.setTriggeredBy(triggeredBy);
        executionRepository.save(execution);

        try {
            NexflowContextObject nco = engine.execute(flowId, execution.getId().toString(), payload);
            execution.setStatus(nco.getMeta().getStatus());
            execution.setNcoSnapshot(objectMapper.convertValue(nco, Map.class));
            execution.setCompletedAt(Instant.now());

        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.error("Flow {} execution failed: {}", flowId, msg, ex);
            execution.setStatus(ExecutionStatus.FAILURE);
            execution.setCompletedAt(Instant.now());
        }

        return executionRepository.save(execution);
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
            triggerFlow(flowId, payload, triggeredBy);
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.error("Async flow {} failed: {}", flowId, msg, ex);
        }
    }
}
