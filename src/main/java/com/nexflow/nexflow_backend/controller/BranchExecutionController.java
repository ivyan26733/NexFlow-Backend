package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.BranchExecution;
import com.nexflow.nexflow_backend.repository.BranchExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
@Slf4j
public class BranchExecutionController {

    private final BranchExecutionRepository branchRepo;

    @GetMapping("/{executionId}/branches")
    public ResponseEntity<List<BranchExecution>> getBranches(@PathVariable UUID executionId) {
        List<BranchExecution> branches = branchRepo.findByExecutionId(executionId);
        log.info("[BranchExecutionController] GET branches executionId={} count={}", executionId, branches.size());
        return ResponseEntity.ok(branches);
    }

    @GetMapping("/{executionId}/branches/{forkNodeId}")
    public ResponseEntity<List<BranchExecution>> getBranchesByFork(
            @PathVariable UUID executionId,
            @PathVariable UUID forkNodeId) {
        List<BranchExecution> branches =
                branchRepo.findByExecutionIdAndForkNodeId(executionId, forkNodeId);
        log.info("[BranchExecutionController] GET branches executionId={} forkNodeId={} count={}",
                executionId, forkNodeId, branches.size());
        return ResponseEntity.ok(branches);
    }
}

