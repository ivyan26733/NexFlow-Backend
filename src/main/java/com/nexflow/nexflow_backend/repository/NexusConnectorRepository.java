package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.NexusConnector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NexusConnectorRepository extends JpaRepository<NexusConnector, UUID> {
    List<NexusConnector> findAllByOrderByCreatedAtDesc();
}
