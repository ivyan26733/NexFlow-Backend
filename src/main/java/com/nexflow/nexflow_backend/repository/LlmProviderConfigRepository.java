package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.LlmProvider;
import com.nexflow.nexflow_backend.model.domain.LlmProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmProviderConfigRepository extends JpaRepository<LlmProviderConfig, UUID> {

    // ── Per-user queries (all new code uses these) ───────────────────────────
    List<LlmProviderConfig> findByUserId(UUID userId);

    Optional<LlmProviderConfig> findByUserIdAndProvider(UUID userId, LlmProvider provider);

    List<LlmProviderConfig> findByUserIdAndEnabledTrue(UUID userId);

    // ── Legacy global queries (kept for AiNodeExecutor fallback only) ────────
    Optional<LlmProviderConfig> findByProvider(LlmProvider provider);

    List<LlmProviderConfig> findByEnabledTrue();

    boolean existsByProvider(LlmProvider provider);
}
