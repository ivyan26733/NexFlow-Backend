package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.ChannelConnection;
import com.nexflow.nexflow_backend.model.domain.ChannelPlatform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelConnectionRepository extends JpaRepository<ChannelConnection, UUID> {

    List<ChannelConnection> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<ChannelConnection> findAllByOrderByCreatedAtDesc();

    Optional<ChannelConnection> findByPlatformAndWebhookKey(ChannelPlatform platform, String webhookKey);

    List<ChannelConnection> findByFlowId(UUID flowId);
}
