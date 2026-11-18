package com.nexflow.nexflow_backend.model.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * A file uploaded by a user, received from a chat channel, or produced by a FILE node.
 * The bytes live on disk under app.files.dir; this row is the metadata.
 */
@Entity
@Table(name = "stored_files", indexes = {
        @Index(name = "idx_stored_files_user", columnList = "user_id, created_at"),
        @Index(name = "idx_stored_files_execution", columnList = "execution_id")
})
@Data
public class StoredFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Owner. Files a flow writes or receives belong to the flow's owner. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Column(name = "mime_type", length = 160)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Path relative to app.files.dir. */
    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    /** UPLOAD, FLOW, SLACK, TELEGRAM, WHATSAPP, DISCORD. */
    @Column(nullable = false, length = 32)
    private String source = "UPLOAD";

    @Column(name = "flow_id")
    private UUID flowId;

    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
