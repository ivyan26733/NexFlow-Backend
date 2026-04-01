package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.NodeExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NodeExecutionRepository extends JpaRepository<NodeExecution, UUID> {

    List<NodeExecution> findByExecutionIdOrderByStartedAtAsc(UUID executionId);
}
