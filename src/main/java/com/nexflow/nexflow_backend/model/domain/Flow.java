package com.nexflow.nexflow_backend.model.domain;

import com.nexflow.nexflow_backend.FlowStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "flows")
@Data
public class Flow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Public trigger key (e.g. authService). Used for POST /api/pulse/{slug}. */
    @Column(unique = true)
    private String slug = "";

    private String description;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    private FlowStatus status = FlowStatus.DRAFT;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }
}
