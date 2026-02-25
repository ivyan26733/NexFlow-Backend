package com.nexflow.nexflow_backend.controller;

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

    /** POST /api/pulse/{slugOrId} — trigger by slug (e.g. flow-abc123) or by flow UUID. */
    @PostMapping("/{slugOrId}")
    public ResponseEntity<Execution> trigger(
            @PathVariable String slugOrId,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestHeader(value = "X-Wait-For-Subscriber", required = false) String waitForSubscriber) {

        UUID flowId = resolveFlowId(slugOrId);
        boolean delayStart = "1".equals(waitForSubscriber);
        if (waitForSubscriber != null) {
            log.info("[WS-DEBUG] PulseController: X-Wait-For-Subscriber={}, delayStart={}", waitForSubscriber, delayStart);
        }
        Execution result = flowService.triggerFlow(flowId, payload != null ? payload : Map.of(), "PULSE", delayStart);
        return ResponseEntity.ok(result);
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
