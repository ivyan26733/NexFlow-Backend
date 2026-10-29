package com.nexflow.nexflow_backend.service;

import com.nexflow.nexflow_backend.model.domain.BranchExecution;
import com.nexflow.nexflow_backend.model.dto.BranchResult;
import com.nexflow.nexflow_backend.repository.BranchExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Saves FORK branch records in their own transaction so they commit even though flows run on
 * pool threads without a surrounding transaction.
 */
@Service
@RequiredArgsConstructor
public class BranchExecutionPersistenceService {

    private final BranchExecutionRepository branchExecutionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveBranchExecution(String executionId, UUID forkNodeId, BranchResult result) {
        BranchExecution record = new BranchExecution();
        record.setExecutionId(UUID.fromString(executionId));
        record.setForkNodeId(forkNodeId);
        record.setBranchName(result.branchName());
        record.setStatus(result.status());
        record.setDurationMs(result.durationMs());
        record.setNexSnapshot(result.isSuccess() ? result.nex() : null);
        record.setErrorMessage(result.errorMessage());
        record.setCompletedAt(LocalDateTime.now());
        branchExecutionRepository.save(record);
    }
}
