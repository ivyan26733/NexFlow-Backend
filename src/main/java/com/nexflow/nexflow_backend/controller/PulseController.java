package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.FlowStatus;
import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import com.nexflow.nexflow_backend.service.FlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
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
        Flow flow = flowRepository.findById(flowId).orElseThrow(() -> new IllegalArgumentException("Flow not found: " + slugOrId));

        // Studio uses a two-step start so the UI can subscribe before the engine begins.
        // External callers skip that handshake and start immediately.
        log.info(
                "[Pulse] trigger slugOrId={} flowId={} studio={} flowStatus={} payloadKeys={}",
                slugOrId,
                flowId,
                isStudioTrigger,
                flow.getStatus(),
                safePayload.keySet()
        );

        if (isStudioTrigger) {
            // MODE A: two-phase — Studio will call /api/executions/{id}/start later
            Execution execution = flowService.prepareExecution(flowId, safePayload, "STUDIO");
            log.info("[Pulse] studio prepare executionId={} flowId={}", execution.getId(), flowId);
            return ResponseEntity.ok(execution);
        } else {
            // MODE B: single-phase — external callers, start immediately
            if (flow.getStatus() != FlowStatus.ACTIVE) {
                log.warn("[Pulse] rejected external trigger flowId={} status={} (requires ACTIVE)", flowId, flow.getStatus());
                return ResponseEntity.status(403).build();
            }
            Execution execution = flowService.prepareAndStartExecution(flowId, safePayload, "PULSE");
            log.info("[Pulse] external started executionId={} flowId={}", execution.getId(), flowId);
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
