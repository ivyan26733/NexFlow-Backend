package com.nexflow.nexflow_backend.channel;

import com.nexflow.nexflow_backend.FlowStatus;
import com.nexflow.nexflow_backend.model.domain.ChannelConnection;
import com.nexflow.nexflow_backend.model.domain.ChannelPlatform;
import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.model.domain.StoredFile;
import com.nexflow.nexflow_backend.model.domain.UserRole;
import com.nexflow.nexflow_backend.repository.ChannelConnectionRepository;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import com.nexflow.nexflow_backend.service.FileStorageService;
import com.nexflow.nexflow_backend.service.FlowService;
import com.nexflow.nexflow_backend.service.GroupService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Chat channels: manage connections, and turn incoming messages into flow runs.
 *
 * An incoming message becomes the run's trigger body:
 * <pre>
 * { "text": "...", "command": "/report", "args": "march",
 *   "file": {fileId, name, …}, "files": [...],
 *   "message": { id, text, chatId, threadId, from: {id, name, handle}, timestamp, files, replyContext },
 *   "channel": { platform, connectionId, label } }
 * </pre>
 * so flows read {{nex.text}}, {{nex.message.from.name}}, {{nex.file}} the same way whichever app sent it.
 */
@Slf4j
@Service
public class ChannelService {

    /** Webhooks are answered right away; downloading attachments and starting the run happens here. */
    private final ExecutorService inbound = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "channel-inbound");
        t.setDaemon(true);
        return t;
    });

    /** Recently seen message ids per connection — platforms retry deliveries, a message should run once. */
    private final Map<UUID, Set<String>> recent = new ConcurrentHashMap<>();
    private static final int RECENT_PER_CONNECTION = 500;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Credential fields each platform needs. Secrets are never sent back to the browser. */
    static final Map<ChannelPlatform, List<String>> REQUIRED = Map.of(
            ChannelPlatform.SLACK, List.of("botToken", "signingSecret"),
            ChannelPlatform.TELEGRAM, List.of("botToken"),
            ChannelPlatform.WHATSAPP, List.of("accessToken", "phoneNumberId", "appSecret"),
            ChannelPlatform.DISCORD, List.of("publicKey", "applicationId"));

    /** Values that identify the account rather than unlock it — safe to show. */
    private static final Set<String> NON_SECRET = Set.of("phoneNumberId", "applicationId", "publicKey", "verifyToken");

    private final ChannelConnectionRepository connections;
    private final FlowRepository flows;
    private final GroupService groupService;
    private final FlowService flowService;
    private final FileStorageService storage;
    private final ChannelMessenger messenger;
    private final String publicBaseUrl;

    public ChannelService(ChannelConnectionRepository connections, FlowRepository flows, GroupService groupService,
                          FlowService flowService, FileStorageService storage, ChannelMessenger messenger,
                          @Value("${app.public-base-url:http://localhost:8090}") String publicBaseUrl) {
        this.connections = connections;
        this.flows = flows;
        this.groupService = groupService;
        this.flowService = flowService;
        this.storage = storage;
        this.messenger = messenger;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    @PreDestroy
    void shutdown() {
        inbound.shutdown();
    }

    // ══ Management ═══════════════════════════════════════════════════════════

    public record ChannelRequest(ChannelPlatform platform, String label, UUID flowId, Boolean enabled,
                                 Map<String, String> credentials, Map<String, Object> settings) {}

    public record ChannelResponse(String id, String platform, String label, String flowId, String flowName,
                                  String flowStatus, boolean enabled, String webhookUrl,
                                  Map<String, Object> credentials, List<String> missing, Map<String, Object> settings,
                                  long eventCount, Instant lastEventAt, String lastExecutionId, String lastError,
                                  Instant createdAt) {}

    public List<ChannelResponse> list(NexUser user) {
        List<ChannelConnection> found = user.getRole() == UserRole.ADMIN
                ? connections.findAllByOrderByCreatedAtDesc()
                : connections.findByUserIdOrderByCreatedAtDesc(user.getId());
        return found.stream().map(this::toResponse).toList();
    }

    public ChannelResponse get(UUID id, NexUser user) {
        return toResponse(owned(id, user));
    }

    public ChannelResponse create(ChannelRequest req, NexUser user) {
        if (req.platform() == null) throw new IllegalArgumentException("Choose a platform: Slack, Telegram, WhatsApp or Discord.");
        ChannelConnection c = new ChannelConnection();
        c.setUserId(user.getId());
        c.setPlatform(req.platform());
        c.setWebhookKey(randomToken(24));
        c.getCredentials().put("secretToken", randomToken(32));
        if (req.platform() == ChannelPlatform.WHATSAPP) c.getCredentials().put("verifyToken", randomToken(16));
        apply(c, req, user);
        return toResponse(connections.save(c));
    }

    public ChannelResponse update(UUID id, ChannelRequest req, NexUser user) {
        ChannelConnection c = owned(id, user);
        if (req.platform() != null && req.platform() != c.getPlatform()) {
            throw new IllegalArgumentException("A channel's platform can't be changed. Create a new channel instead.");
        }
        apply(c, req, user);
        return toResponse(connections.save(c));
    }

    public void delete(UUID id, NexUser user) {
        connections.delete(owned(id, user));
    }

    public ChannelResponse toggle(UUID id, NexUser user) {
        ChannelConnection c = owned(id, user);
        c.setEnabled(!c.isEnabled());
        return toResponse(connections.save(c));
    }

    /** Checks the credentials against the platform. */
    public String test(UUID id, NexUser user) {
        return messenger.check(owned(id, user));
    }

    /** Points the Telegram bot at our webhook URL (Telegram is the only platform that allows this via API). */
    public String registerWebhook(UUID id, NexUser user) {
        ChannelConnection c = owned(id, user);
        if (c.getPlatform() != ChannelPlatform.TELEGRAM) {
            throw new IllegalArgumentException("Only Telegram webhooks can be registered automatically. "
                    + "Paste the webhook URL into the " + displayName(c.getPlatform()) + " app settings instead.");
        }
        String url = webhookUrl(c);
        if (!url.startsWith("https://")) {
            throw new IllegalArgumentException("Telegram needs a public https:// address. Set PUBLIC_BASE_URL on the server "
                    + "(currently " + publicBaseUrl + ").");
        }
        messenger.registerTelegramWebhook(c, url);
        return "Telegram will now send this bot's messages to NexFlow.";
    }

    public ChannelConnection owned(UUID id, NexUser user) {
        ChannelConnection c = connections.findById(id).orElseThrow(() -> new IllegalArgumentException("Channel not found."));
        if (user.getRole() != UserRole.ADMIN && !user.getId().equals(c.getUserId())) {
            throw new AccessDeniedException("No access to this channel");
        }
        return c;
    }

    private void apply(ChannelConnection c, ChannelRequest req, NexUser user) {
        String label = req.label() != null && !req.label().isBlank() ? req.label().trim() : displayName(c.getPlatform()) + " bot";
        c.setLabel(label);
        if (req.flowId() != null) {
            Flow flow = flows.findById(req.flowId()).orElseThrow(() -> new IllegalArgumentException("Flow not found."));
            if (!groupService.hasFlowAccess(flow.getId(), flow.getUserId(), user)) throw new AccessDeniedException("No access to this flow");
        }
        c.setFlowId(req.flowId());
        if (req.enabled() != null) c.setEnabled(req.enabled());
        if (req.credentials() != null) {
            Map<String, String> merged = new HashMap<>(c.getCredentials() != null ? c.getCredentials() : Map.of());
            req.credentials().forEach((k, v) -> {
                // Blank or masked values mean "keep what's saved".
                if (v == null || v.isBlank() || v.contains("•")) return;
                if (k.equals("secretToken")) return; // generated by us
                merged.put(k, v.trim());
            });
            c.setCredentials(merged);
        }
        if (req.settings() != null) c.setSettings(new HashMap<>(req.settings()));
        if (c.getPlatform() == ChannelPlatform.DISCORD && c.credential("publicKey") != null
                && !ChannelSignatures.isDiscordPublicKey(c.credential("publicKey"))) {
            throw new IllegalArgumentException("The Discord public key should be 64 hex characters (General Information → Public Key).");
        }
    }

    private ChannelResponse toResponse(ChannelConnection c) {
        Flow flow = c.getFlowId() != null ? flows.findById(c.getFlowId()).orElse(null) : null;
        Map<String, Object> creds = new LinkedHashMap<>();
        if (c.getCredentials() != null) {
            c.getCredentials().forEach((k, v) -> {
                if (k.equals("secretToken") || v == null || v.isBlank()) return;
                creds.put(k, NON_SECRET.contains(k) ? v : mask(v));
            });
        }
        List<String> missing = REQUIRED.get(c.getPlatform()).stream().filter(k -> c.credential(k) == null).toList();
        return new ChannelResponse(c.getId().toString(), c.getPlatform().name(), c.getLabel(),
                c.getFlowId() != null ? c.getFlowId().toString() : null,
                flow != null ? flow.getName() : c.getFlowId() != null ? "Deleted flow" : null,
                flow != null ? flow.getStatus().name() : null,
                c.isEnabled(), webhookUrl(c), creds, missing,
                c.getSettings() != null ? c.getSettings() : Map.of(),
                c.getEventCount(), c.getLastEventAt(),
                c.getLastExecutionId() != null ? c.getLastExecutionId().toString() : null,
                c.getLastError(), c.getCreatedAt());
    }

    public String webhookUrl(ChannelConnection c) {
        return publicBaseUrl + "/api/hooks/" + c.getPlatform().name().toLowerCase() + "/" + c.getWebhookKey();
    }

    // ══ Incoming messages ════════════════════════════════════════════════════

    public ChannelConnection findByWebhook(ChannelPlatform platform, String key) {
        return connections.findByPlatformAndWebhookKey(platform, key).orElse(null);
    }

    /** Queues incoming messages; returns immediately so the webhook answers within the platform's deadline. */
    public void accept(ChannelConnection c, List<ChannelInbound.Message> messages) {
        for (ChannelInbound.Message m : messages) {
            if (!firstTime(c.getId(), m.dedupeKey())) {
                log.debug("[Channel] '{}' duplicate delivery {} ignored", c.getLabel(), m.dedupeKey());
                continue;
            }
            try {
                inbound.execute(() -> handle(c.getId(), m));
            } catch (RejectedExecutionException e) {
                log.warn("[Channel] '{}' dropped a message — server shutting down", c.getLabel());
            }
        }
    }

    /** Why a message would be ignored before it even reaches the queue (null = it will run). */
    public String readiness(ChannelConnection c) {
        if (!c.isEnabled()) return "This channel is paused in NexFlow.";
        if (c.getFlowId() == null) return "This channel isn't connected to a flow yet.";
        Flow flow = flows.findById(c.getFlowId()).orElse(null);
        if (flow == null) return "The flow this channel starts was deleted.";
        if (flow.getStatus() != FlowStatus.ACTIVE) return "The flow '" + flow.getName() + "' isn't ACTIVE.";
        return null;
    }

    void handle(UUID connectionId, ChannelInbound.Message m) {
        ChannelConnection c = connections.findById(connectionId).orElse(null);
        if (c == null) return;
        try {
            String notReady = readiness(c);
            if (notReady != null) {
                record(c, null, notReady);
                return;
            }
            String prefix = c.setting("triggerPrefix") instanceof String p ? p.trim() : "";
            if (!prefix.isEmpty() && m.command() == null && (m.text() == null || !m.text().toLowerCase().startsWith(prefix.toLowerCase()))) {
                return; // not addressed to this bot
            }
            Flow flow = flows.findById(c.getFlowId()).orElseThrow();
            UUID owner = flow.getUserId() != null ? flow.getUserId() : c.getUserId();

            List<StoredFile> saved = new ArrayList<>();
            List<String> fileErrors = new ArrayList<>();
            boolean download = !Boolean.FALSE.equals(c.setting("downloadFiles"));
            if (download) {
                for (ChannelInbound.Attachment a : m.attachments()) {
                    try {
                        byte[] bytes = messenger.download(c, a);
                        saved.add(storage.store(owner, a.name(), a.mimeType(), bytes, c.getPlatform().name(), flow.getId(), null));
                    } catch (Exception e) {
                        fileErrors.add(a.name() + ": " + e.getMessage());
                        log.warn("[Channel] '{}' could not download {}: {}", c.getLabel(), a.name(), e.getMessage());
                    }
                }
            }

            Execution execution = flowService.start(flow.getId(), payload(c, m, saved, fileErrors), c.getPlatform().name());
            record(c, execution.getId(), fileErrors.isEmpty() ? null : "Some attachments couldn't be downloaded: " + String.join("; ", fileErrors));
            log.info("[Channel] '{}' message from {} started flow '{}' executionId={}", c.getLabel(), m.fromHandle(), flow.getName(), execution.getId());
        } catch (Exception e) {
            log.error("[Channel] '{}' could not handle a message: {}", c.getLabel(), e.getMessage(), e);
            record(c, null, "Couldn't start the flow: " + e.getMessage());
        }
    }

    /** The trigger body a flow receives for one chat message. */
    Map<String, Object> payload(ChannelConnection c, ChannelInbound.Message m, List<StoredFile> saved, List<String> fileErrors) {
        List<Map<String, Object>> files = saved.stream().map(storage::describe).toList();

        Map<String, Object> from = new LinkedHashMap<>();
        from.put("id", m.fromId());
        from.put("name", m.fromName() != null ? m.fromName() : m.fromHandle());
        from.put("handle", m.fromHandle());

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", m.messageId());
        message.put("text", m.text());
        message.put("command", m.command());
        message.put("args", m.args());
        message.put("chatId", m.chatId());
        message.put("threadId", m.threadId());
        message.put("from", from);
        message.put("timestamp", m.timestamp() != null ? m.timestamp().toString() : null);
        message.put("files", files);
        message.put("replyContext", m.replyContext());
        if (!fileErrors.isEmpty()) message.put("fileErrors", fileErrors);

        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("platform", c.getPlatform().name());
        channel.put("connectionId", c.getId().toString());
        channel.put("label", c.getLabel());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", m.text() != null ? m.text() : "");
        body.put("command", m.command());
        body.put("args", m.args() != null ? m.args() : m.text());
        if (!files.isEmpty()) body.put("file", files.get(0));
        body.put("files", files);
        body.put("message", message);
        body.put("channel", channel);
        return body;
    }

    private void record(ChannelConnection c, UUID executionId, String error) {
        try {
            ChannelConnection fresh = connections.findById(c.getId()).orElse(null);
            if (fresh == null) return;
            fresh.setEventCount(fresh.getEventCount() + 1);
            fresh.setLastEventAt(Instant.now());
            if (executionId != null) fresh.setLastExecutionId(executionId);
            fresh.setLastError(error != null && error.length() > 1000 ? error.substring(0, 1000) : error);
            connections.save(fresh);
        } catch (Exception e) {
            log.warn("[Channel] could not record event for '{}': {}", c.getLabel(), e.getMessage());
        }
    }

    private boolean firstTime(UUID connectionId, String key) {
        Set<String> seen = recent.computeIfAbsent(connectionId, id -> Collections.synchronizedSet(new LinkedHashSet<>()));
        synchronized (seen) {
            if (!seen.add(key)) return false;
            if (seen.size() > RECENT_PER_CONNECTION) {
                var it = seen.iterator();
                it.next();
                it.remove();
            }
            return true;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    public static String displayName(ChannelPlatform p) {
        return switch (p) {
            case SLACK -> "Slack";
            case TELEGRAM -> "Telegram";
            case WHATSAPP -> "WhatsApp";
            case DISCORD -> "Discord";
        };
    }

    static String mask(String secret) {
        return secret.length() <= 8 ? "••••••" : secret.substring(0, 4) + "••••••" + secret.substring(secret.length() - 4);
    }

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
