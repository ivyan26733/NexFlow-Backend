package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.model.domain.StoredFile;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import com.nexflow.nexflow_backend.service.FileStorageService;
import com.nexflow.nexflow_backend.service.FlowService;
import com.nexflow.nexflow_backend.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * File storage for users and flows.
 *
 *   GET    /api/files                      — my files
 *   POST   /api/files                      — upload (multipart "file")
 *   GET    /api/files/{id}                 — metadata
 *   GET    /api/files/{id}/download        — bytes (authenticated)
 *   DELETE /api/files/{id}
 *   GET    /api/files/public/{id}?exp&sig  — bytes via a signed link (public)
 *   POST   /api/flows/{flowId}/run-with-file — upload file(s) and start the flow with them as input
 */
@RestController
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService storage;
    private final FlowRepository flowRepository;
    private final GroupService groupService;
    private final FlowService flowService;

    @GetMapping("/api/files")
    public List<Map<String, Object>> list(@AuthenticationPrincipal NexUser user) {
        return storage.listFor(user).stream().map(storage::describe).toList();
    }

    @PostMapping(value = "/api/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @AuthenticationPrincipal NexUser user) throws IOException {
        return storage.describe(save(file, user.getId(), null));
    }

    @GetMapping("/api/files/{id}")
    public Map<String, Object> get(@PathVariable UUID id, @AuthenticationPrincipal NexUser user) {
        return storage.describe(storage.findAccessible(id, user));
    }

    @GetMapping("/api/files/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @AuthenticationPrincipal NexUser user) {
        return bytes(storage.findAccessible(id, user));
    }

    @DeleteMapping("/api/files/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal NexUser user) {
        storage.delete(storage.findAccessible(id, user));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/files/public/{id}")
    public ResponseEntity<?> publicDownload(@PathVariable UUID id,
                                            @RequestParam("exp") long expires,
                                            @RequestParam("sig") String signature) {
        if (!storage.verifySignature(id, expires, signature)) {
            return ResponseEntity.status(403).body(Map.of("error", "This download link is invalid or has expired."));
        }
        return bytes(storage.find(id));
    }

    /** Studio "Run with file": drafts allowed, same as POST /api/flows/{id}/run. */
    @PostMapping(value = "/api/flows/{flowId}/run-with-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Execution> runWithFile(@PathVariable UUID flowId,
                                                 @RequestParam("file") List<MultipartFile> uploads,
                                                 @RequestParam(value = "payload", required = false) String payloadJson,
                                                 @AuthenticationPrincipal NexUser user) throws IOException {
        Flow flow = flowRepository.findById(flowId).orElse(null);
        if (flow == null) return ResponseEntity.notFound().build();
        if (user == null || !groupService.hasFlowAccess(flow.getId(), flow.getUserId(), user)) {
            return ResponseEntity.status(403).build();
        }
        Map<String, Object> payload = storage.uploadPayload(uploads, payloadJson, flow.getUserId() != null ? flow.getUserId() : user.getId(), flow.getId());
        return ResponseEntity.ok(flowService.start(flowId, payload, "STUDIO"));
    }

    private StoredFile save(MultipartFile upload, UUID ownerId, UUID flowId) throws IOException {
        if (upload.isEmpty()) throw new IllegalArgumentException("The file is empty.");
        return storage.store(ownerId, upload.getOriginalFilename(), upload.getContentType(), upload.getBytes(), "UPLOAD", flowId, null);
    }

    private ResponseEntity<byte[]> bytes(StoredFile file) {
        MediaType type;
        try {
            type = MediaType.parseMediaType(file.getMimeType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getName(), StandardCharsets.UTF_8).build().toString())
                .body(storage.read(file));
    }
}
