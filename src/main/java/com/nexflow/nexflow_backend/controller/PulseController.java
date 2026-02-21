package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.Execution;
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

    // POST /api/pulse/{flowId} — trigger a flow with a payload from Postman or any HTTP client
    @PostMapping("/{flowId}")
    public ResponseEntity<Execution> trigger(
            @PathVariable UUID flowId,
            @RequestBody(required = false) Map<String, Object> payload) {

        Execution result = flowService.triggerFlow(flowId, payload != null ? payload : Map.of());
        return ResponseEntity.ok(result);
    }
}
