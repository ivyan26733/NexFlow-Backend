package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.channel.ChannelMessenger.ChannelException;
import com.nexflow.nexflow_backend.channel.ChannelService;
import com.nexflow.nexflow_backend.channel.ChannelService.ChannelRequest;
import com.nexflow.nexflow_backend.channel.ChannelService.ChannelResponse;
import com.nexflow.nexflow_backend.model.domain.NexUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Manage chat channel connections (Slack, Telegram, WhatsApp, Discord). */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;

    @GetMapping
    public List<ChannelResponse> list(@AuthenticationPrincipal NexUser user) {
        return channelService.list(user);
    }

    @GetMapping("/{id}")
    public ChannelResponse get(@PathVariable UUID id, @AuthenticationPrincipal NexUser user) {
        return channelService.get(id, user);
    }

    @PostMapping
    public ChannelResponse create(@RequestBody ChannelRequest request, @AuthenticationPrincipal NexUser user) {
        return channelService.create(request, user);
    }

    @PutMapping("/{id}")
    public ChannelResponse update(@PathVariable UUID id, @RequestBody ChannelRequest request,
                                  @AuthenticationPrincipal NexUser user) {
        return channelService.update(id, request, user);
    }

    @PatchMapping("/{id}/toggle")
    public ChannelResponse toggle(@PathVariable UUID id, @AuthenticationPrincipal NexUser user) {
        return channelService.toggle(id, user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal NexUser user) {
        channelService.delete(id, user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable UUID id, @AuthenticationPrincipal NexUser user) {
        return Map.of("success", true, "message", channelService.test(id, user));
    }

    @PostMapping("/{id}/register-webhook")
    public Map<String, Object> registerWebhook(@PathVariable UUID id, @AuthenticationPrincipal NexUser user) {
        return Map.of("success", true, "message", channelService.registerWebhook(id, user));
    }

    /** Platform errors are the user's to fix (wrong token, missing scope …) — show them as a 400, not a 500. */
    @ExceptionHandler(ChannelException.class)
    public ResponseEntity<Map<String, Object>> platformError(ChannelException e) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
    }
}
