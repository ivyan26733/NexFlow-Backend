package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.FlowSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FlowScheduleRepository extends JpaRepository<FlowSchedule, UUID> {

    List<FlowSchedule> findAllByOrderByCreatedAtDesc();

    List<FlowSchedule> findByFlowIdInOrderByCreatedAtDesc(Collection<UUID> flowIds);

    List<FlowSchedule> findByEnabledTrueAndNextRunAtLessThanEqual(Instant now);

    @Transactional
    void deleteByFlowId(UUID flowId);

    /**
     * Moves a due schedule to its next run time, but only if nobody else did first.
     * Returns 1 for the caller that won the claim, 0 otherwise — this is what prevents double runs.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowSchedule s SET s.nextRunAt = :next WHERE s.id = :id AND s.enabled = true AND s.nextRunAt = :due")
    int claimDueRun(@Param("id") UUID id, @Param("due") Instant due, @Param("next") Instant next);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowSchedule s SET s.lastRunAt = :at, s.lastExecutionId = :executionId WHERE s.id = :id")
    void recordRun(@Param("id") UUID id, @Param("at") Instant at, @Param("executionId") UUID executionId);
}
