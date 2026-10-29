package com.nexflow.nexflow_backend.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleServiceTest {

    @Test
    void nextRunIsEvaluatedInTheScheduleTimezone() {
        // 09:00 every day in Kolkata (UTC+5:30) is 03:30 UTC
        Instant after = Instant.parse("2026-10-29T00:00:00Z");
        Instant next = ScheduleService.nextRun("0 0 9 * * *", "Asia/Kolkata", after);
        assertThat(next).isEqualTo(Instant.parse("2026-10-29T03:30:00Z"));
    }

    @Test
    void nextRunIsStrictlyAfterTheGivenTime() {
        Instant at = Instant.parse("2026-10-29T10:15:00Z");
        assertThat(ScheduleService.nextRun("0 */15 * * * *", "UTC", at)).isEqualTo(Instant.parse("2026-10-29T10:30:00Z"));
    }

    @Test
    void weekdayOnlyScheduleSkipsTheWeekend() {
        Instant friday = Instant.parse("2026-10-30T12:00:00Z"); // a Friday, after 09:00
        assertThat(ScheduleService.nextRun("0 0 9 * * MON-FRI", "UTC", friday))
                .isEqualTo(Instant.parse("2026-11-02T09:00:00Z")); // Monday
    }

    @Test
    void invalidCronIsRejectedWithAFriendlyMessage() {
        assertThatThrownBy(() -> ScheduleService.validate("every day at 9", "UTC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isn't a valid cron expression");
    }

    @Test
    void unknownTimezoneIsRejected() {
        assertThatThrownBy(() -> ScheduleService.validate("0 0 9 * * *", "Mars/Olympus"))
                .hasMessageContaining("Unknown time zone");
    }

    @Test
    void schedulesFasterThanOncePerMinuteAreRejected() {
        assertThatThrownBy(() -> ScheduleService.validate("*/10 * * * * *", "UTC"))
                .hasMessageContaining("at most once a minute");
    }

    @Test
    void everyMinuteIsAllowed() {
        ScheduleService.validate("0 * * * * *", "UTC");
    }
}
