package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    List<StoredFile> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<StoredFile> findAllByOrderByCreatedAtDesc();

    List<StoredFile> findByExecutionIdOrderByCreatedAtDesc(UUID executionId);
}
