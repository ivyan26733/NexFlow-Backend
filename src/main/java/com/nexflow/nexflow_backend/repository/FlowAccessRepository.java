package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.FlowAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlowAccessRepository extends JpaRepository<FlowAccess, UUID> {
    List<FlowAccess> findByFlowId(UUID flowId);
    List<FlowAccess> findByTargetTypeAndTargetId(String targetType, UUID targetId);
    Optional<FlowAccess> findByFlowIdAndTargetTypeAndTargetId(UUID flowId, String targetType, UUID targetId);
    void deleteByFlowIdAndTargetTypeAndTargetId(UUID flowId, String targetType, UUID targetId);
}
