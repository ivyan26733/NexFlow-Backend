package com.nexflow.nexflow_backend.service;

import com.nexflow.nexflow_backend.model.domain.Announcement;
import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.repository.AnnouncementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnnouncementService {

    public static final Set<String> CATEGORIES = Set.of("GENERAL", "UPDATE", "FEATURE", "MAINTENANCE", "INCIDENT");
    static final int MAX_TITLE = 160;
    static final int MAX_BODY = 10_000;

    /** Pinned first, then newest first (by publish time, or creation time for drafts). */
    static final Comparator<Announcement> ORDER = Comparator
            .comparing(Announcement::isPinned).reversed()
            .thenComparing(a -> a.getPublishedAt() != null ? a.getPublishedAt() : a.getCreatedAt(), Comparator.reverseOrder());

    private final AnnouncementRepository repository;

    public record AnnouncementRequest(String title, String body, String category, Boolean pinned, Boolean published) {}

    public List<Announcement> list(boolean includeDrafts) {
        List<Announcement> all = includeDrafts ? repository.findAll() : repository.findByPublishedTrue();
        return all.stream().sorted(ORDER).toList();
    }

    public Announcement create(AnnouncementRequest req, NexUser author) {
        Announcement a = new Announcement();
        a.setAuthorId(author.getId());
        a.setAuthorName(author.getName() != null && !author.getName().isBlank() ? author.getName() : author.getEmail());
        apply(a, req, true);
        return repository.save(a);
    }

    public Announcement update(UUID id, AnnouncementRequest req) {
        Announcement a = repository.findById(id).orElseThrow(() -> new NoSuchElementException("Announcement not found"));
        apply(a, req, false);
        return repository.save(a);
    }

    public void delete(UUID id) {
        if (!repository.existsById(id)) throw new NoSuchElementException("Announcement not found");
        repository.deleteById(id);
    }

    /** Validates and copies the request. On update, fields left null keep their current value. */
    static void apply(Announcement a, AnnouncementRequest req, boolean creating) {
        if (req == null) throw new IllegalArgumentException("Nothing to save");
        if (creating || req.title() != null) {
            String title = req.title() == null ? "" : req.title().trim();
            if (title.isEmpty()) throw new IllegalArgumentException("Give the announcement a title.");
            if (title.length() > MAX_TITLE) throw new IllegalArgumentException("The title can be at most " + MAX_TITLE + " characters.");
            a.setTitle(title);
        }
        if (creating || req.body() != null) {
            String body = req.body() == null ? "" : req.body().strip();
            if (body.isEmpty()) throw new IllegalArgumentException("Write the announcement text.");
            if (body.length() > MAX_BODY) throw new IllegalArgumentException("The text can be at most " + MAX_BODY + " characters.");
            a.setBody(body);
        }
        if (req.category() != null || creating) {
            String category = req.category() == null || req.category().isBlank() ? "GENERAL" : req.category().trim().toUpperCase(Locale.ROOT);
            if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("Unknown category: " + req.category());
            a.setCategory(category);
        }
        if (req.pinned() != null) a.setPinned(req.pinned());
        if (req.published() != null) {
            if (req.published() && !a.isPublished()) a.setPublishedAt(Instant.now());
            a.setPublished(req.published());
        }
    }
}
