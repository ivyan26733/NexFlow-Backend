package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.FlowSaveLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface FlowSaveLogRepository extends JpaRepository<FlowSaveLog, UUID> {

    /** Canvas saves and flow-creates for a set of flows after a cutoff instant. */
    List<FlowSaveLog> findByFlowIdInAndSavedAtAfter(List<UUID> flowIds, Instant since);
}
