package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.service.AssistantService;
import com.nexflow.nexflow_backend.service.AssistantService.AssistantReply;
import com.nexflow.nexflow_backend.service.AssistantService.ChatContext;
import com.nexflow.nexflow_backend.service.AssistantService.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint for the NexFlow Assistant chatbot.
 * POST /api/assistant/chat — send a message, get a reply (plus optional action buttons).
 *
 * Request:  { "message", "history": [{role, content}], "pageContext": {path, name},
 *             "flow": {nodes, edges} (Studio canvas), "transactions": [...] (Studio recent runs) }
 * Response: { "reply": "markdown", "actions": [{type: "RUN_FLOW", flowId, flowName, payload, label}],
 *             "toolsUsed": ["list_executions", …], "provider": "ANTHROPIC" }
 */
@Slf4j
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    /** Chat with the NexFlow Assistant (shapes in the class comment). */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body,
                                                     @AuthenticationPrincipal NexUser user) {
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        String message = body.get("message") instanceof String m ? m : null;
        if (message == null || message.trim().length() < 2) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Message must be at least 2 characters."));
        }

        List<ChatMessage> history = Collections.emptyList();
        if (body.get("history") instanceof List<?> rawList) {
            history = rawList.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> {
                        Map<?, ?> map = (Map<?, ?>) item;
                        Object role = map.get("role");
                        Object content = map.get("content");
                        return new ChatMessage(role != null ? role.toString() : "user", content != null ? content.toString() : "");
                    })
                    .toList();
        }

        // Optional context: page the user is on, the Studio canvas, and that flow's recent runs.
        Map<String, String> page = new java.util.HashMap<>();
        if (body.get("pageContext") instanceof Map<?, ?> pc) {
            pc.forEach((k, v) -> { if (k != null && v != null) page.put(k.toString(), v.toString()); });
        }
        ChatContext context = new ChatContext(page, body.get("flow"), body.get("transactions"));

        try {
            AssistantReply reply = assistantService.chat(message.trim(), history, context, user);
            log.debug("[Assistant] chat ok replyChars={} tools={}", reply.reply().length(), reply.toolsUsed().size());
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("reply", reply.reply());
            out.put("actions", reply.actions());
            out.put("toolsUsed", reply.toolsUsed().stream().map(AssistantService.ToolUse::tool).toList());
            out.put("provider", reply.provider());
            return ResponseEntity.ok(out);
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
