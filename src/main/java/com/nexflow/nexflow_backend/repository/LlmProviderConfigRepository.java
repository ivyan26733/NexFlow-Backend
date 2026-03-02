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

    Optional<LlmProviderConfig> findByProvider(LlmProvider provider);

    List<LlmProviderConfig> findByEnabledTrue();

    boolean existsByProvider(LlmProvider provider);
}
