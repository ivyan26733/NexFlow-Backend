CREATE TABLE IF NOT EXISTS announcements (
    id           UUID PRIMARY KEY,
    title        VARCHAR(160) NOT NULL,
    body         TEXT         NOT NULL,
    category     VARCHAR(32)  NOT NULL DEFAULT 'GENERAL',
    pinned       BOOLEAN      NOT NULL DEFAULT FALSE,
    published    BOOLEAN      NOT NULL DEFAULT FALSE,
    author_id    UUID,
    author_name  VARCHAR(255),
    created_at   TIMESTAMP(6) WITH TIME ZONE,
    updated_at   TIMESTAMP(6) WITH TIME ZONE,
    published_at TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_announcements_published ON announcements (published, published_at DESC);
