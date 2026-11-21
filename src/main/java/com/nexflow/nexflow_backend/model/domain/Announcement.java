package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** A message from an admin to everyone using this NexFlow instance (updates, maintenance, new features…). */
@Entity
@Table(name = "announcements")
@Data
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    /** GENERAL, UPDATE, FEATURE, MAINTENANCE or INCIDENT — kept as text so new categories need no migration. */
    @Column(nullable = false, length = 32)
    private String category = "GENERAL";

    /** Pinned announcements are shown as a banner at the top of every page until dismissed. */
    private boolean pinned;

    /** Drafts are only visible to admins. */
    private boolean published;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "author_name")
    private String authorName;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }
}
