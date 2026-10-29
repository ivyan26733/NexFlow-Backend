package com.nexflow.nexflow_backend.service;

import com.nexflow.nexflow_backend.FlowStatus;
import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.model.domain.FlowSchedule;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import com.nexflow.nexflow_backend.repository.FlowScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Every few seconds, starts the flows whose schedule is due.
 *
 * Each due schedule is first "claimed" by moving its nextRunAt forward with a conditional update,
 * so even with several app instances (or a slow previous tick) a schedule fires once per due time.
 * Missed runs while the app was down are not replayed — the schedule simply resumes at its next time.
 * Only ACTIVE flows are started, through the same path as a public webhook call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleRunner {

    private final FlowScheduleRepository schedules;
    private final FlowRepository flows;
    private final FlowService flowService;

    @Scheduled(fixedDelayString = "${app.schedules.poll-ms:5000}", initialDelayString = "${app.schedules.initial-delay-ms:15000}")
    public void runDueSchedules() {
        Instant now = Instant.now();
        for (FlowSchedule schedule : schedules.findByEnabledTrueAndNextRunAtLessThanEqual(now)) {
            try {
                fire(schedule, now);
            } catch (Exception e) {
                log.error("[Schedule] '{}' could not run: {}", schedule.getLabel(), e.getMessage(), e);
            }
        }
    }

    void fire(FlowSchedule schedule, Instant now) {
        Instant next = ScheduleService.nextRun(schedule.getCronExpression(), schedule.getTimezone(), now);
        if (schedules.claimDueRun(schedule.getId(), schedule.getNextRunAt(), next) != 1) return; // someone else took it

        Flow flow = flows.findById(schedule.getFlowId()).orElse(null);
        if (flow == null || flow.getStatus() != FlowStatus.ACTIVE) {
            log.info("[Schedule] '{}' skipped — flow is {}", schedule.getLabel(), flow == null ? "deleted" : flow.getStatus());
            return;
        }
        Map<String, Object> payload = schedule.getPayload() != null ? schedule.getPayload() : Map.of();
        Execution execution = flowService.start(flow.getId(), payload, "SCHEDULE");
        schedules.recordRun(schedule.getId(), now, execution.getId());
        log.info("[Schedule] '{}' started flow '{}' executionId={} next={}", schedule.getLabel(), flow.getName(), execution.getId(), next);
    }
}
