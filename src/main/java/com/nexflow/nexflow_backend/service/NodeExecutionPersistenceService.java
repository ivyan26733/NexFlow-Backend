package com.nexflow.nexflow_backend.service;

import com.nexflow.nexflow_backend.model.domain.NodeExecution;
import com.nexflow.nexflow_backend.repository.NodeExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;

/**
 * Saves node execution records (branch nodes) in a new transaction so persistence
 * commits even when the caller runs on a pool thread without a transaction context.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NodeExecutionPersistenceService {

    private final NodeExecutionRepository nodeExecutionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(NodeExecution source) {
        NodeExecution record = new NodeExecution();
        record.setExecutionId(source.getExecutionId());
        record.setNodeId(source.getNodeId());
        record.setNodeLabel(source.getNodeLabel());
        record.setNodeType(source.getNodeType());
        record.setBranchName(source.getBranchName());
        record.setStatus(source.getStatus());
        record.setInputNex(source.getInputNex() != null ? new LinkedHashMap<>(source.getInputNex()) : null);
        record.setOutputNex(source.getOutputNex() != null ? new LinkedHashMap<>(source.getOutputNex()) : null);
        record.setDurationMs(source.getDurationMs());
        record.setErrorMessage(source.getErrorMessage());
        record.setStartedAt(source.getStartedAt());
        record.setFinishedAt(source.getFinishedAt());
        nodeExecutionRepository.save(record);
    }
}
