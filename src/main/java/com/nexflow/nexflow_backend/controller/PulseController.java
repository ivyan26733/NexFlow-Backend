package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.FlowStatus;
import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import com.nexflow.nexflow_backend.service.FileStorageService;
import com.nexflow.nexflow_backend.service.FlowService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public webhook: POST /api/pulse/{slugOrId} starts an ACTIVE flow with the request body as its input.
 * Returns immediately with the execution record; the run continues in the background.
 * POST /api/pulse/{slugOrId}/file does the same with uploaded file(s) (multipart "file", optional "payload" JSON).
 * (Studio runs draft flows through the authenticated POST /api/flows/{id}/run instead.)
 */
@RestController
@RequestMapping("/api/pulse")
@RequiredArgsConstructor
public class PulseController {

    private final FlowService flowService;
    private final FlowRepository flowRepository;
    private final FileStorageService fileStorage;

    @PostMapping("/{slugOrId}")
    public ResponseEntity<?> trigger(@PathVariable String slugOrId,
                                     @RequestBody(required = false) Map<String, Object> payload) {
        Optional<Flow> flow = findFlow(slugOrId);
        if (flow.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No flow found for '" + slugOrId + "'."));
        }
        if (flow.get().getStatus() != FlowStatus.ACTIVE) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "The flow '" + flow.get().getName() + "' isn't active. Set it to ACTIVE before calling it from outside."));
        }
        Execution execution = flowService.start(flow.get().getId(), payload != null ? payload : Map.of(), "PULSE");
        return ResponseEntity.ok(execution);
    }

    @PostMapping(value = "/{slugOrId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> triggerWithFile(@PathVariable String slugOrId,
                                             @RequestParam("file") List<MultipartFile> uploads,
                                             @RequestParam(value = "payload", required = false) String payloadJson) throws IOException {
        Optional<Flow> flow = findFlow(slugOrId);
        if (flow.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No flow found for '" + slugOrId + "'."));
        }
        if (flow.get().getStatus() != FlowStatus.ACTIVE) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "The flow '" + flow.get().getName() + "' isn't active. Set it to ACTIVE before calling it from outside."));
        }
        if (flow.get().getUserId() == null) {
            return ResponseEntity.status(400).body(Map.of("error", "This flow has no owner, so it can't receive files."));
        }
        Map<String, Object> payload = fileStorage.uploadPayload(uploads, payloadJson, flow.get().getUserId(), flow.get().getId());
        return ResponseEntity.ok(flowService.start(flow.get().getId(), payload, "PULSE"));
    }

    private Optional<Flow> findFlow(String slugOrId) {
        try {
            return flowRepository.findById(UUID.fromString(slugOrId));
        } catch (IllegalArgumentException notAUuid) {
            return flowRepository.findBySlug(slugOrId);
        }
    }
}
