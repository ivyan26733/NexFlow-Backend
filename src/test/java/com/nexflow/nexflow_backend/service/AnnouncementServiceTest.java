package com.nexflow.nexflow_backend.service;

import com.nexflow.nexflow_backend.model.domain.Announcement;
import com.nexflow.nexflow_backend.service.AnnouncementService.AnnouncementRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnouncementServiceTest {

    @Test
    void createsWithDefaultsAndStampsPublishTime() {
        Announcement a = new Announcement();
        AnnouncementService.apply(a, new AnnouncementRequest("  New nodes  ", "File and Message steps are live.", null, null, true), true);
        assertThat(a.getTitle()).isEqualTo("New nodes");
        assertThat(a.getCategory()).isEqualTo("GENERAL");
        assertThat(a.isPublished()).isTrue();
        assertThat(a.getPublishedAt()).isNotNull();
    }

    @Test
    void draftsHaveNoPublishTimeAndUpdatesKeepUnsetFields() {
        Announcement a = new Announcement();
        AnnouncementService.apply(a, new AnnouncementRequest("Maintenance", "Down at 2am", "maintenance", true, false), true);
        assertThat(a.getPublishedAt()).isNull();
        assertThat(a.getCategory()).isEqualTo("MAINTENANCE");

        AnnouncementService.apply(a, new AnnouncementRequest(null, null, null, null, true), false);
        assertThat(a.getTitle()).isEqualTo("Maintenance");
        assertThat(a.isPinned()).isTrue();
        assertThat(a.getPublishedAt()).isNotNull();
    }

    @Test
    void rejectsMissingTextAndUnknownCategory() {
        assertThatThrownBy(() -> AnnouncementService.apply(new Announcement(), new AnnouncementRequest(" ", "x", null, null, null), true))
                .hasMessageContaining("title");
        assertThatThrownBy(() -> AnnouncementService.apply(new Announcement(), new AnnouncementRequest("t", "", null, null, null), true))
                .hasMessageContaining("text");
        assertThatThrownBy(() -> AnnouncementService.apply(new Announcement(), new AnnouncementRequest("t", "b", "party", null, null), true))
                .hasMessageContaining("category");
    }

    @Test
    void pinnedFirstThenNewest() {
        Announcement old = at("old", false, 100), recent = at("recent", false, 300), pinned = at("pinned", true, 50);
        List<String> order = Stream.of(old, recent, pinned).sorted(AnnouncementService.ORDER).map(Announcement::getTitle).toList();
        assertThat(order).containsExactly("pinned", "recent", "old");
    }

    private static Announcement at(String title, boolean pinned, long seconds) {
        Announcement a = new Announcement();
        a.setTitle(title);
        a.setPinned(pinned);
        a.setPublished(true);
        a.setPublishedAt(Instant.ofEpochSecond(seconds));
        return a;
    }
}
