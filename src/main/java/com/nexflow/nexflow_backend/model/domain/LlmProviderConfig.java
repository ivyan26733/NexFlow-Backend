package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "llm_provider_configs")
public class LlmProviderConfig {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private LlmProvider provider;

    @Column(name = "api_key", nullable = false)
    private String apiKey;

    @Column(name = "custom_endpoint")
    private String customEndpoint;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public UUID getId() { return id; }
    public LlmProvider getProvider() { return provider; }
    public String getApiKey() { return apiKey; }
    public String getCustomEndpoint() { return customEndpoint; }
    public boolean isEnabled() { return enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setId(UUID id) { this.id = id; }
    public void setProvider(LlmProvider provider) { this.provider = provider; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public void setCustomEndpoint(String ep) { this.customEndpoint = ep; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setCreatedAt(LocalDateTime t) { this.createdAt = t; }
    public void setUpdatedAt(LocalDateTime t) { this.updatedAt = t; }
}
