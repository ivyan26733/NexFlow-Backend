package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.nco.ExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {
    // Lists read only the small columns (see ExecutionRow); full runs are loaded one at a time by id
    List<ExecutionRow> findRowsByFlowIdOrderByStartedAtDesc(UUID flowId);
     // All executions newest-first — used by the Transactions page
     List<ExecutionRow> findAllByOrderByStartedAtDesc();

     // Scoped to a set of flow IDs — used for non-admin users
     List<ExecutionRow> findByFlowIdInOrderByStartedAtDesc(List<UUID> flowIds);
     List<Execution> findByStatusOrderByStartedAtDesc(ExecutionStatus status);

     // Count executions per flow — used for pulse stats
     long countByFlowId(UUID flowId);

     // Recent executions for AI diagnostics
     List<ExecutionRow> findByFlowIdAndStartedAtAfterOrderByStartedAtDesc(UUID flowId, Instant since);

     // Transactions page — time windows (startedAt is always set on insert)
     List<ExecutionRow> findByStartedAtAfterOrderByStartedAtDesc(Instant since);

     List<ExecutionRow> findByStartedAtBetweenOrderByStartedAtDesc(Instant fromInclusive, Instant toInclusive);

     List<ExecutionRow> findByFlowIdInAndStartedAtAfterOrderByStartedAtDesc(List<UUID> flowIds, Instant since);

     List<ExecutionRow> findByFlowIdInAndStartedAtBetweenOrderByStartedAtDesc(List<UUID> flowIds, Instant fromInclusive, Instant toInclusive);

     @Query("SELECT MAX(e.startedAt) FROM Execution e")
     Optional<Instant> findMaxStartedAt();

     @Query("SELECT MAX(e.startedAt) FROM Execution e WHERE e.flowId IN :flowIds")
     Optional<Instant> findMaxStartedAtByFlowIdIn(@Param("flowIds") List<UUID> flowIds);

     @Query("SELECT e.status, COUNT(e) FROM Execution e WHERE e.flowId IN :flowIds GROUP BY e.status")
     List<Object[]> countByStatusForFlowIds(@Param("flowIds") List<UUID> flowIds);

     @Query("SELECT e.status, COUNT(e) FROM Execution e GROUP BY e.status")
     List<Object[]> countByStatusAll();
}
