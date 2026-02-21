package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.Flow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FlowRepository extends JpaRepository<Flow, UUID> {}
