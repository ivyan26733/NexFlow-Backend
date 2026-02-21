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

    public Execution triggerFlow(UUID flowId, Map<String, Object> payload) {
        flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));

        Execution execution = new Execution();
        execution.setFlowId(flowId);
        execution.setTriggeredBy("PULSE");
        executionRepository.save(execution);

        try {
            NexflowContextObject nco = engine.execute(flowId, execution.getId().toString(), payload);

            execution.setStatus(nco.getMeta().getStatus());
            execution.setNcoSnapshot(objectMapper.convertValue(nco, Map.class));
            execution.setCompletedAt(Instant.now());

        } catch (Exception ex) {
            log.error("Flow {} execution failed: {}", flowId, ex.getMessage());
            execution.setStatus(ExecutionStatus.FAILURE);
            execution.setCompletedAt(Instant.now());
        }

        return executionRepository.save(execution);
    }
}
