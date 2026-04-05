package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_groups")
@Data
public class UserGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** If true, every member of this group can access all flows. */
    @Column(nullable = false)
    private boolean allFlowsAccess = false;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
