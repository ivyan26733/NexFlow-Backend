package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.FlowNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FlowNodeRepository extends JpaRepository<FlowNode, UUID> {
    List<FlowNode> findByFlowId(UUID flowId);
}
