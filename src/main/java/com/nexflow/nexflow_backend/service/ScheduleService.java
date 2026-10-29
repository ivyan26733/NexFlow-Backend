package com.nexflow.nexflow_backend.service;

import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.model.domain.FlowSchedule;
import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.model.domain.UserRole;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import com.nexflow.nexflow_backend.repository.FlowScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Create / edit / list cron schedules. A user can manage schedules of any flow they have access to.
 * Running due schedules is done by {@link ScheduleRunner}.
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

    /** Protects the server from schedules that would fire every few seconds. */
    static final Duration MIN_INTERVAL = Duration.ofMinutes(1);

    private final FlowScheduleRepository schedules;
    private final FlowRepository flows;
    private final GroupService groupService;

    public record ScheduleRequest(UUID flowId, String label, String cronExpression, String timezone,
                                  Boolean enabled, Map<String, Object> payload) {}

    /** Shape expected by the frontend (FlowSchedule in index.ts). */
    public record ScheduleResponse(String id, String flowId, String flowName, String flowSlug, String flowStatus,
                                   String userId, String label, String cronExpression, String timezone,
                                   boolean enabled, Map<String, Object> payload, Instant lastRunAt,
                                   Instant nextRunAt, String lastExecutionId, Instant createdAt, Instant updatedAt) {}

    public List<ScheduleResponse> list(NexUser user) {
        List<Flow> visible = flows.findAll().stream().filter(f -> canAccess(f, user)).toList();
        Map<UUID, Flow> byId = visible.stream().collect(Collectors.toMap(Flow::getId, Function.identity()));
        List<FlowSchedule> found = user.getRole() == UserRole.ADMIN
                ? schedules.findAllByOrderByCreatedAtDesc()
                : schedules.findByFlowIdInOrderByCreatedAtDesc(byId.keySet());
        return found.stream().map(s -> toResponse(s, byId.get(s.getFlowId()))).toList();
    }

    public ScheduleResponse create(ScheduleRequest req, NexUser user) {
        if (req.flowId() == null) throw new IllegalArgumentException("Choose which flow to schedule.");
        Flow flow = accessibleFlow(req.flowId(), user);
        FlowSchedule schedule = new FlowSchedule();
        schedule.setFlowId(flow.getId());
        schedule.setUserId(user.getId());
        apply(schedule, req, flow);
        return toResponse(schedules.save(schedule), flow);
    }

    public ScheduleResponse update(UUID id, ScheduleRequest req, NexUser user) {
        FlowSchedule schedule = find(id);
        Flow flow = accessibleFlow(schedule.getFlowId(), user);
        apply(schedule, req, flow);
        return toResponse(schedules.save(schedule), flow);
    }

    public ScheduleResponse toggle(UUID id, NexUser user) {
        FlowSchedule schedule = find(id);
        Flow flow = accessibleFlow(schedule.getFlowId(), user);
        schedule.setEnabled(!schedule.isEnabled());
        schedule.setNextRunAt(schedule.isEnabled() ? nextRun(schedule.getCronExpression(), schedule.getTimezone(), Instant.now()) : null);
        return toResponse(schedules.save(schedule), flow);
    }

    public void delete(UUID id, NexUser user) {
        FlowSchedule schedule = find(id);
        accessibleFlow(schedule.getFlowId(), user);
        schedules.delete(schedule);
    }

    // ── Cron helpers ─────────────────────────────────────────────────────────

    /** Next time {@code cron} fires after {@code after}, evaluated in {@code timezone}; null if it never fires again. */
    public static Instant nextRun(String cron, String timezone, Instant after) {
        ZonedDateTime next = CronExpression.parse(cron).next(after.atZone(ZoneId.of(timezone)));
        return next != null ? next.toInstant().truncatedTo(ChronoUnit.SECONDS) : null;
    }

    static void validate(String cron, String timezone) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That schedule isn't a valid cron expression. Use 6 parts: "
                    + "second minute hour day-of-month month day-of-week (for example \"0 0 9 * * MON-FRI\").");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown time zone '" + timezone + "'.");
        }
        ZonedDateTime first = expression.next(ZonedDateTime.now(zone));
        if (first == null) throw new IllegalArgumentException("That schedule never runs. Check the date fields.");
        ZonedDateTime second = expression.next(first);
        if (second != null && Duration.between(first, second).compareTo(MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException("Schedules can run at most once a minute.");
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void apply(FlowSchedule schedule, ScheduleRequest req, Flow flow) {
        String cron = req.cronExpression() != null ? req.cronExpression().trim() : "";
        String timezone = req.timezone() != null && !req.timezone().isBlank() ? req.timezone().trim() : "UTC";
        validate(cron, timezone);
        schedule.setCronExpression(cron);
        schedule.setTimezone(timezone);
        schedule.setLabel(req.label() != null && !req.label().isBlank() ? req.label().trim() : flow.getName());
        schedule.setPayload(req.payload());
        schedule.setEnabled(req.enabled() == null || req.enabled());
        schedule.setNextRunAt(schedule.isEnabled() ? nextRun(cron, timezone, Instant.now()) : null);
    }

    private FlowSchedule find(UUID id) {
        return schedules.findById(id).orElseThrow(() -> new IllegalArgumentException("Schedule not found."));
    }

    private Flow accessibleFlow(UUID flowId, NexUser user) {
        Flow flow = flows.findById(flowId).orElseThrow(() -> new IllegalArgumentException("Flow not found."));
        if (!canAccess(flow, user)) throw new AccessDeniedException("No access to this flow");
        return flow;
    }

    private boolean canAccess(Flow flow, NexUser user) {
        return groupService.hasFlowAccess(flow.getId(), flow.getUserId(), user);
    }

    private static ScheduleResponse toResponse(FlowSchedule s, Flow flow) {
        return new ScheduleResponse(
                s.getId().toString(),
                s.getFlowId().toString(),
                flow != null ? flow.getName() : "Deleted flow",
                flow != null ? flow.getSlug() : "",
                flow != null ? flow.getStatus().name() : "DELETED",
                s.getUserId() != null ? s.getUserId().toString() : null,
                s.getLabel(),
                s.getCronExpression(),
                s.getTimezone(),
                s.isEnabled(),
                s.getPayload() != null ? s.getPayload() : Map.of(),
                s.getLastRunAt(),
                s.getNextRunAt(),
                s.getLastExecutionId() != null ? s.getLastExecutionId().toString() : null,
                s.getCreatedAt(),
                s.getUpdatedAt());
    }
}
