package com.nexflow.nexflow_backend.service;

import com.nexflow.nexflow_backend.model.domain.NodeExecution;
import com.nexflow.nexflow_backend.repository.NodeExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves branch node runs in their own transaction so they commit even though flows run on
 * pool threads without a surrounding transaction. One row per run (a node inside a loop gets one per pass).
 */
@Service
@RequiredArgsConstructor
public class NodeExecutionPersistenceService {

    private final NodeExecutionRepository nodeExecutionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(NodeExecution record) {
        nodeExecutionRepository.save(record);
    }
}
