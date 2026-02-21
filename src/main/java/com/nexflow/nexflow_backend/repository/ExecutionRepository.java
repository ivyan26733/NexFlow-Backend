package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.Execution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {
    List<Execution> findByFlowIdOrderByStartedAtDesc(UUID flowId);
}
