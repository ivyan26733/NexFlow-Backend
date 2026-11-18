package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A bot / app on a chat platform, connected to NexFlow.
 *
 * Incoming messages arrive at /api/hooks/{platform}/{webhookKey} and start {@link #flowId} (when set and
 * the flow is ACTIVE). MESSAGE nodes use the same credentials to send replies.
 */
@Entity
@Table(name = "channel_connections", indexes = {
        @Index(name = "idx_channel_connections_user", columnList = "user_id"),
        @Index(name = "idx_channel_connections_flow", columnList = "flow_id")
})
@Data
public class ChannelConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChannelPlatform platform;

    @Column(nullable = false)
    private String label;

    /** Flow started by incoming messages. Null = this connection is only used for sending. */
    @Column(name = "flow_id")
    private UUID flowId;

    /** When false, incoming messages are acknowledged but ignored. */
    @Column(nullable = false)
    private boolean enabled = true;

    /** Unguessable part of the webhook URL. */
    @Column(name = "webhook_key", nullable = false, unique = true, length = 64)
    private String webhookKey;

    /**
     * Platform secrets: botToken, signingSecret (Slack), secretToken (Telegram), accessToken / phoneNumberId /
     * appSecret / verifyToken (WhatsApp), publicKey / applicationId (Discord). Never returned by the API.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> credentials = new HashMap<>();

    /** triggerPrefix (only messages starting with it start the flow), downloadFiles, ackMessage. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> settings = new HashMap<>();

    @Column(name = "event_count", nullable = false)
    private long eventCount;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "last_execution_id")
    private UUID lastExecutionId;

    /** Why the last incoming message didn't start a run (cleared on success). */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public String credential(String key) {
        String value = credentials != null ? credentials.get(key) : null;
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    public Object setting(String key) {
        return settings != null ? settings.get(key) : null;
    }
}
