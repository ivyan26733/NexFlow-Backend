package com.nexflow.nexflow_backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.channel.ChannelInbound;
import com.nexflow.nexflow_backend.channel.ChannelService;
import com.nexflow.nexflow_backend.channel.ChannelSignatures;
import com.nexflow.nexflow_backend.model.domain.ChannelConnection;
import com.nexflow.nexflow_backend.model.domain.ChannelPlatform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Public webhooks the chat platforms call: /api/hooks/{slack|telegram|whatsapp|discord}/{webhookKey}.
 *
 * Every request is verified with the platform's signature (or secret header) before anything runs.
 * Replies are sent immediately; the flow runs in the background (see ChannelService).
 */
@Slf4j
@RestController
@RequestMapping("/api/hooks")
@RequiredArgsConstructor
public class ChannelWebhookController {

    private final ChannelService channels;
    private final ObjectMapper objectMapper;

    // ── Slack ────────────────────────────────────────────────────────────────

    @PostMapping("/slack/{key}")
    public ResponseEntity<?> slack(@PathVariable String key,
                                   @RequestBody(required = false) String body,
                                   @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
                                   @RequestHeader(value = "X-Slack-Signature", required = false) String signature,
                                   @RequestHeader(value = "X-Slack-Retry-Num", required = false) String retry,
                                   @RequestHeader(value = "Content-Type", required = false) String contentType) throws Exception {
        ChannelConnection c = channels.findByWebhook(ChannelPlatform.SLACK, key);
        if (c == null) return notFound();
        String raw = body != null ? body : "";
        if (!ChannelSignatures.slack(c.credential("signingSecret"), timestamp, raw, signature, Instant.now().getEpochSecond())) {
            return unauthorized("Slack signature check failed — check the Signing Secret.");
        }

        if (contentType != null && contentType.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
            Map<String, String> form = ChannelInbound.parseForm(raw);
            if (form.containsKey("payload")) return ResponseEntity.ok().build(); // interactive components: not used
            String notReady = channels.readiness(c);
            if (notReady != null) return ResponseEntity.ok(Map.of("response_type", "ephemeral", "text", notReady));
            channels.accept(c, List.of(ChannelInbound.slackCommand(form)));
            return ResponseEntity.ok(Map.of("response_type", "ephemeral", "text", ack(c)));
        }

        JsonNode root = objectMapper.readTree(raw);
        if ("url_verification".equals(root.path("type").asText())) {
            return ResponseEntity.ok(Map.of("challenge", root.path("challenge").asText()));
        }
        if (retry != null) return ResponseEntity.ok().build(); // Slack retried because we were slow; the first delivery is already running
        if ("event_callback".equals(root.path("type").asText())) {
            ChannelInbound.Message m = ChannelInbound.slackEvent(root);
            if (m != null) channels.accept(c, List.of(m));
        }
        return ResponseEntity.ok().build();
    }

    // ── Telegram ─────────────────────────────────────────────────────────────

    @PostMapping("/telegram/{key}")
    public ResponseEntity<?> telegram(@PathVariable String key,
                                      @RequestBody(required = false) String body,
                                      @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret) throws Exception {
        ChannelConnection c = channels.findByWebhook(ChannelPlatform.TELEGRAM, key);
        if (c == null) return notFound();
        if (!ChannelSignatures.telegram(c.credential("secretToken"), secret)) {
            return unauthorized("Telegram secret token mismatch — click “Register webhook” again.");
        }
        ChannelInbound.Message m = ChannelInbound.telegram(objectMapper.readTree(body != null ? body : "{}"));
        if (m != null) channels.accept(c, List.of(m));
        return ResponseEntity.ok().build();
    }

    // ── WhatsApp ─────────────────────────────────────────────────────────────

    /** Meta's one-time subscription check. */
    @GetMapping("/whatsapp/{key}")
    public ResponseEntity<String> whatsappVerify(@PathVariable String key,
                                                 @RequestParam(value = "hub.mode", required = false) String mode,
                                                 @RequestParam(value = "hub.verify_token", required = false) String token,
                                                 @RequestParam(value = "hub.challenge", required = false) String challenge) {
        ChannelConnection c = channels.findByWebhook(ChannelPlatform.WHATSAPP, key);
        if (c == null) return ResponseEntity.status(404).body("Unknown webhook");
        if ("subscribe".equals(mode) && ChannelSignatures.secretMatches(c.credential("verifyToken"), token)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
        }
        return ResponseEntity.status(403).body("Verify token mismatch");
    }

    @PostMapping("/whatsapp/{key}")
    public ResponseEntity<?> whatsapp(@PathVariable String key,
                                      @RequestBody(required = false) String body,
                                      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) throws Exception {
        ChannelConnection c = channels.findByWebhook(ChannelPlatform.WHATSAPP, key);
        if (c == null) return notFound();
        String raw = body != null ? body : "";
        if (!ChannelSignatures.whatsapp(c.credential("appSecret"), raw, signature)) {
            return unauthorized("WhatsApp signature check failed — check the App Secret.");
        }
        List<ChannelInbound.Message> messages = ChannelInbound.whatsapp(objectMapper.readTree(raw));
        if (!messages.isEmpty()) channels.accept(c, messages);
        return ResponseEntity.ok().build();
    }

    // ── Discord ──────────────────────────────────────────────────────────────

    @PostMapping("/discord/{key}")
    public ResponseEntity<?> discord(@PathVariable String key,
                                     @RequestBody(required = false) String body,
                                     @RequestHeader(value = "X-Signature-Ed25519", required = false) String signature,
                                     @RequestHeader(value = "X-Signature-Timestamp", required = false) String timestamp) throws Exception {
        ChannelConnection c = channels.findByWebhook(ChannelPlatform.DISCORD, key);
        if (c == null) return notFound();
        String raw = body != null ? body : "";
        if (!ChannelSignatures.discord(c.credential("publicKey"), timestamp, raw, signature)) {
            return unauthorized("invalid request signature");
        }
        JsonNode interaction = objectMapper.readTree(raw);
        int type = interaction.path("type").asInt();
        if (type == 1) return ResponseEntity.ok(Map.of("type", 1)); // PING
        if (type != 2) return ResponseEntity.ok(Map.of("type", 4, "data", Map.of("content", "This action isn't supported.", "flags", 64)));

        String notReady = channels.readiness(c);
        if (notReady != null) return ResponseEntity.ok(Map.of("type", 4, "data", Map.of("content", notReady, "flags", 64)));
        channels.accept(c, List.of(ChannelInbound.discordCommand(interaction)));
        return ResponseEntity.ok(Map.of("type", 4, "data", Map.of("content", ack(c))));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String ack(ChannelConnection c) {
        Object custom = c.setting("ackMessage");
        return custom instanceof String s && !s.isBlank() ? s : "Got it — working on it…";
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(Map.of("error", "Unknown webhook"));
    }

    private static ResponseEntity<?> unauthorized(String why) {
        log.warn("[Hooks] rejected webhook: {}", why);
        return ResponseEntity.status(401).body(Map.of("error", why));
    }
}
