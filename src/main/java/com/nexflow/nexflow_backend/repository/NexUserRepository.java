package com.nexflow.nexflow_backend.repository;

import com.nexflow.nexflow_backend.model.domain.NexUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NexUserRepository extends JpaRepository<NexUser, UUID> {
    Optional<NexUser> findByEmail(String email);
    Optional<NexUser> findByGoogleId(String googleId);
    boolean existsByEmail(String email);
    long count();
}
