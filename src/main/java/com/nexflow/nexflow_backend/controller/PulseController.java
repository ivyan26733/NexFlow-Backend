package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import com.nexflow.nexflow_backend.service.FlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/pulse")
@RequiredArgsConstructor
public class PulseController {

    private final FlowService flowService;
    private final FlowRepository flowRepository;

    /**
     * Trigger a flow execution.
     *
     * Two modes controlled by X-Studio-Trigger header:
     *
     * MODE A — Studio browser trigger (X-Studio-Trigger: 1)
     *   Phase 1 only: creates execution record, returns executionId.
     *   Does NOT start execution.
     *
     * MODE B — External trigger (no header, default)
     *   Prepare + start immediately in one shot.
     *   Used by: JMeter, curl, webhooks, scheduled pulses, API consumers.
     */
    @PostMapping("/{slugOrId}")
    public ResponseEntity<Execution> trigger(
            @PathVariable String slugOrId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Studio-Trigger", required = false) String studioTrigger) {

        UUID flowId = resolveFlowId(slugOrId);
        Map<String, Object> safePayload = payload != null ? payload : Map.of();
        boolean isStudioTrigger = "1".equals(studioTrigger);

        if (isStudioTrigger) {
            // MODE A: two-phase — Studio will call /api/executions/{id}/start later
            Execution execution = flowService.prepareExecution(flowId, safePayload, "PULSE");
            return ResponseEntity.ok(execution);
        } else {
            // MODE B: single-phase — external callers, start immediately
            Execution execution = flowService.prepareAndStartExecution(flowId, safePayload, "PULSE");
            return ResponseEntity.ok(execution);
        }
    }

    private UUID resolveFlowId(String slugOrId) {
        if (slugOrId == null || slugOrId.isBlank()) {
            throw new IllegalArgumentException("Missing flow slug or id");
        }
        if (slugOrId.length() == 36 && slugOrId.charAt(8) == '-' && slugOrId.charAt(13) == '-') {
            try {
                UUID id = UUID.fromString(slugOrId);
                return flowRepository.findById(id)
                        .map(Flow::getId)
                        .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + slugOrId));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Flow not found: " + slugOrId);
            }
        }
        return flowRepository.findBySlug(slugOrId)
                .map(Flow::getId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found for slug: " + slugOrId));
    }
}
