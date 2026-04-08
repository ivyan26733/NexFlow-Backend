package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.service.AssistantService;
import com.nexflow.nexflow_backend.service.AssistantService.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint for the NexFlow Assistant chatbot.
 * POST /api/assistant/chat — send a message, get a reply.
 */
@Slf4j
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    /**
     * Chat with the NexFlow Assistant.
     *
     * Request body:
     *   { "message": "How do I use the VARIABLE node?",
     *     "history": [{ "role": "user", "content": "..." }, { "role": "assistant", "content": "..." }] }
     *
     * Response:
     *   { "reply": "The VARIABLE node lets you define..." }
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(@RequestBody Map<String, Object> body) {
        String message = (String) body.get("message");
        if (message == null || message.trim().length() < 2) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Message must be at least 2 characters."));
        }

        // Parse history from request body
        List<ChatMessage> history = Collections.emptyList();
        Object historyObj = body.get("history");
        if (historyObj instanceof List<?> rawList) {
            history = rawList.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> {
                        @SuppressWarnings("unchecked")
                        Map<String, String> map = (Map<String, String>) item;
                        return new ChatMessage(
                                map.getOrDefault("role", "user"),
                                map.getOrDefault("content", "")
                        );
                    })
                    .toList();
        }

        try {
            String reply = assistantService.chat(message.trim(), history);
            log.debug("[Assistant] chat ok replyChars={}", reply != null ? reply.length() : 0);
            return ResponseEntity.ok(Map.of("reply", reply));
        } catch (IllegalStateException e) {
            log.warn("[Assistant] chat unavailable: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[Assistant] chat failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Assistant failed: " + e.getMessage()));
        }
    }

    /** Health check — confirms the assistant endpoint is available. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
