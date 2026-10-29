package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.BranchExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BranchExecutionRepository extends JpaRepository<BranchExecution, UUID> {

    List<BranchExecution> findByExecutionId(UUID executionId);

}

