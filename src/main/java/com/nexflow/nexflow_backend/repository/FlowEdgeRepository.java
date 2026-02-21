package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.FlowEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FlowEdgeRepository extends JpaRepository<FlowEdge, UUID> {
    List<FlowEdge> findByFlowId(UUID flowId);
}
