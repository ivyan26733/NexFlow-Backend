package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.UserGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserGroupRepository extends JpaRepository<UserGroup, UUID> {
    List<UserGroup> findByOwnerId(UUID ownerId);
}
