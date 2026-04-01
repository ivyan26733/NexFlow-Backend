package com.nexflow.nexflow_backend.service;

import com.nexflow.nexflow_backend.model.domain.BranchExecution;
import com.nexflow.nexflow_backend.model.domain.BranchExecutionStatus;
import com.nexflow.nexflow_backend.repository.BranchExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Saves branch execution records in a new transaction so persistence commits
 * even when the caller runs on a pool thread without a transaction context.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BranchExecutionPersistenceService {

    private final BranchExecutionRepository branchExecutionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveBranchExecution(
            String executionId,
            UUID forkNodeId,
            String branchName,
            BranchExecutionStatus status,
            long durationMs,
            Map<String, Object> nexSnapshot,
            String errorMessage) {

        BranchExecution record = new BranchExecution();
        record.setExecutionId(UUID.fromString(executionId));
        record.setForkNodeId(forkNodeId);
        record.setBranchName(branchName);
        record.setStatus(status);
        record.setDurationMs(durationMs);
        record.setNexSnapshot(nexSnapshot);
        record.setErrorMessage(errorMessage);
        record.setCompletedAt(LocalDateTime.now());

        branchExecutionRepository.save(record);

        log.info("[ForkNode] Saved branch execution record branchName='{}' status={}",
                branchName, status);
    }
}
