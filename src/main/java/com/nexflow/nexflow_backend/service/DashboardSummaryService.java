package com.nexflow.nexflow_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexflow.nexflow_backend.model.domain.Execution;
import com.nexflow.nexflow_backend.model.domain.Flow;
import com.nexflow.nexflow_backend.model.domain.FlowSaveLog;
import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.model.domain.UserRole;
import com.nexflow.nexflow_backend.repository.ExecutionRepository;
import com.nexflow.nexflow_backend.repository.FlowRepository;
import com.nexflow.nexflow_backend.repository.FlowSaveLogRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DashboardSummaryService {

    private static final Logger log = LoggerFactory.getLogger(DashboardSummaryService.class);

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final FlowRepository flowRepository;
    private final ExecutionRepository executionRepository;
    private final FlowSaveLogRepository flowSaveLogRepository;
    private final GroupService groupService;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    public DashboardSummary getSummary(NexUser user, boolean refresh) {
        if (user == null) throw new IllegalArgumentException("Unauthorized");
        String key = "dashboard:summary:" + user.getId();
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();

        if (!refresh && redis != null) {
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                try {
                    log.info("Dashboard summary for user {} — served from Redis cache", user.getId());
                    return objectMapper.readValue(cached, DashboardSummary.class);
                } catch (Exception ex) {
                    log.warn("Dashboard summary for user {} — invalid cache entry, recomputing from database", user.getId(), ex);
                }
            }
        }

        if (redis == null) {
            log.info("Dashboard summary for user {} — Redis unavailable; computing from database (refresh={})", user.getId(), refresh);
        } else if (refresh) {
            log.info("Dashboard summary for user {} — refresh=true; computing from database and updating cache", user.getId());
        } else {
            log.info("Dashboard summary for user {} — cache miss; computing from database", user.getId());
        }

        DashboardSummary summary = computeSummary(user);

        if (redis != null) {
            try {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(summary), CACHE_TTL);
                log.info("Dashboard summary for user {} — stored in Redis (ttl={})", user.getId(), CACHE_TTL);
            } catch (Exception ex) {
                log.warn("Dashboard summary for user {} — failed to write Redis cache", user.getId(), ex);
            }
        }

        return summary;
    }

    private DashboardSummary computeSummary(NexUser user) {
        List<Flow> flows = getAccessibleFlows(user);
        Map<UUID, Flow> flowsById = new HashMap<>();
        for (Flow f : flows) flowsById.put(f.getId(), f);

        List<Execution> executions = flows.isEmpty()
                ? List.of()
                : executionRepository.findByFlowIdInOrderByStartedAtDesc(flows.stream().map(Flow::getId).toList());

        long success = executions.stream().filter(e -> "SUCCESS".equals(e.getStatus().name())).count();
        long failure = executions.stream().filter(e -> "FAILURE".equals(e.getStatus().name())).count();
        long running = executions.stream().filter(e -> "RUNNING".equals(e.getStatus().name())).count();
        long completed = success + failure;
        int successRate = completed == 0 ? 0 : (int) Math.round((success * 100.0) / completed);

        Flow latestFlow = flows.stream()
                .max(Comparator.comparing(f -> f.getUpdatedAt() != null ? f.getUpdatedAt() : Instant.EPOCH))
                .orElse(null);

        Map<UUID, long[]> agg = new HashMap<>();
        for (Execution e : executions) {
            long[] a = agg.computeIfAbsent(e.getFlowId(), id -> new long[4]); // count, success, failure, totalMs
            a[0]++;
            if ("SUCCESS".equals(e.getStatus().name())) a[1]++;
            if ("FAILURE".equals(e.getStatus().name())) a[2]++;
            if (e.getStartedAt() != null && e.getCompletedAt() != null) {
                a[3] += Duration.between(e.getStartedAt(), e.getCompletedAt()).toMillis();
            }
        }

        String slowestName = null;
        long slowestAvgMs = -1;
        List<TopFlowItem> topFlows = new ArrayList<>();
        for (Map.Entry<UUID, long[]> entry : agg.entrySet()) {
            UUID flowId = entry.getKey();
            long[] a = entry.getValue();
            Flow f = flowsById.get(flowId);
            String flowName = f != null && f.getName() != null ? f.getName() : "Unknown";
            long avgMs = a[0] == 0 ? 0 : Math.round(a[3] / (double) a[0]);
            if (avgMs > slowestAvgMs) {
                slowestAvgMs = avgMs;
                slowestName = flowName;
            }
            topFlows.add(new TopFlowItem(
                    flowId.toString(),
                    flowName,
                    a[0],
                    a[1],
                    a[2],
                    avgMs
            ));
        }
        topFlows.sort((a, b) -> Long.compare(b.count(), a.count()));
        if (topFlows.size() > 5) topFlows = topFlows.subList(0, 5);

        Map<String, Integer> peakBuckets = new HashMap<>();
        for (Execution e : executions) {
            if (e.getStartedAt() == null) continue;
            if (!"PULSE".equals(e.getTriggeredBy())) continue;
            Instant i = e.getStartedAt();
            LocalDate d = i.atZone(ZoneId.systemDefault()).toLocalDate();
            int hour = i.atZone(ZoneId.systemDefault()).getHour();
            String key = d + " " + String.format("%02d:00", hour);
            peakBuckets.put(key, peakBuckets.getOrDefault(key, 0) + 1);
        }
        String peakAt = null;
        int peakCount = 0;
        for (Map.Entry<String, Integer> e : peakBuckets.entrySet()) {
            if (e.getValue() > peakCount) {
                peakCount = e.getValue();
                peakAt = e.getKey();
            }
        }

        List<DailyPerfItem> dailyPerf = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            dailyPerf.add(new DailyPerfItem(day.toString(), day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), 0, 0, 0));
        }
        for (Execution e : executions) {
            if (e.getStartedAt() == null) continue;
            String day = e.getStartedAt().atZone(ZoneId.systemDefault()).toLocalDate().toString();
            for (int i = 0; i < dailyPerf.size(); i++) {
                DailyPerfItem cur = dailyPerf.get(i);
                if (!cur.day().equals(day)) continue;
                if ("SUCCESS".equals(e.getStatus().name())) dailyPerf.set(i, new DailyPerfItem(cur.day(), cur.label(), cur.success() + 1, cur.failure(), cur.running()));
                else if ("FAILURE".equals(e.getStatus().name())) dailyPerf.set(i, new DailyPerfItem(cur.day(), cur.label(), cur.success(), cur.failure() + 1, cur.running()));
                else if ("RUNNING".equals(e.getStatus().name())) dailyPerf.set(i, new DailyPerfItem(cur.day(), cur.label(), cur.success(), cur.failure(), cur.running() + 1));
            }
        }

        List<Integer> hourlyHeat = new ArrayList<>();
        for (int i = 0; i < 24; i++) hourlyHeat.add(0);
        Instant cutoff = Instant.now().minus(Duration.ofDays(7));
        for (Execution e : executions) {
            if (e.getStartedAt() == null || e.getStartedAt().isBefore(cutoff)) continue;
            int hour = e.getStartedAt().atZone(ZoneId.systemDefault()).getHour();
            hourlyHeat.set(hour, hourlyHeat.get(hour) + 1);
        }

        // ── Activity grid: per-day counts for the last 365 days ──────────────
        Map<String, Integer> activityGrid = new LinkedHashMap<>();
        LocalDate gridToday = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        for (int i = 364; i >= 0; i--) {
            activityGrid.put(gridToday.minusDays(i).toString(), 0);
        }
        Instant cutoff365 = Instant.now().minus(Duration.ofDays(365));
        // Executions
        for (Execution e : executions) {
            if (e.getStartedAt() == null || e.getStartedAt().isBefore(cutoff365)) continue;
            String day = e.getStartedAt().atZone(zone).toLocalDate().toString();
            activityGrid.merge(day, 1, Integer::sum);
        }
        // Flow creates
        for (Flow f : flows) {
            if (f.getCreatedAt() == null || f.getCreatedAt().isBefore(cutoff365)) continue;
            String day = f.getCreatedAt().atZone(zone).toLocalDate().toString();
            activityGrid.merge(day, 1, Integer::sum);
        }
        // Canvas saves
        List<UUID> flowIds365 = flows.stream().map(Flow::getId).toList();
        if (!flowIds365.isEmpty()) {
            List<FlowSaveLog> saves = flowSaveLogRepository.findByFlowIdInAndSavedAtAfter(flowIds365, cutoff365);
            for (FlowSaveLog s : saves) {
                String day = s.getSavedAt().atZone(zone).toLocalDate().toString();
                activityGrid.merge(day, 1, Integer::sum);
            }
        }

        List<RecentExecutionItem> recent = executions.stream().limit(5).map(e -> {
            Flow f = flowsById.get(e.getFlowId());
            String flowName = f != null && f.getName() != null ? f.getName() : "Unknown";
            long durationMs = -1;
            if (e.getStartedAt() != null && e.getCompletedAt() != null) {
                durationMs = Duration.between(e.getStartedAt(), e.getCompletedAt()).toMillis();
            }
            return new RecentExecutionItem(
                    e.getId().toString(),
                    e.getFlowId().toString(),
                    flowName,
                    e.getStatus().name(),
                    e.getTriggeredBy(),
                    e.getStartedAt() != null ? e.getStartedAt().toString() : null,
                    durationMs
            );
        }).toList();

        return new DashboardSummary(
                flows.size(),
                executions.size(),
                success,
                failure,
                running,
                successRate,
                latestFlow != null ? latestFlow.getId().toString() : null,
                latestFlow != null ? latestFlow.getName() : null,
                latestFlow != null && latestFlow.getUpdatedAt() != null ? latestFlow.getUpdatedAt().toString() : null,
                slowestName,
                slowestAvgMs < 0 ? null : slowestAvgMs,
                peakAt,
                peakCount == 0 ? null : peakCount,
                dailyPerf,
                hourlyHeat,
                topFlows,
                recent,
                activityGrid
        );
    }

    private List<Flow> getAccessibleFlows(NexUser user) {
        List<Flow> all = flowRepository.findAll();
        if (user.getRole() == UserRole.ADMIN) return all;
        return all.stream().filter(f -> groupService.hasFlowAccess(f.getId(), f.getUserId(), user)).toList();
    }

    public record DashboardSummary(
            long totalFlows,
            long totalExecutions,
            long successCount,
            long failureCount,
            long runningCount,
            int successRate,
            String latestFlowId,
            String latestFlowName,
            String latestFlowUpdatedAt,
            String slowestFlowName,
            Long slowestFlowAvgMs,
            String peakExternalAt,
            Integer peakExternalCount,
            List<DailyPerfItem> dailyPerf,
            List<Integer> hourlyHeat,
            List<TopFlowItem> topFlows,
            List<RecentExecutionItem> recentExecutions,
            Map<String, Integer> activityGrid
    ) {}

    public record DailyPerfItem(String day, String label, int success, int failure, int running) {}

    public record TopFlowItem(String id, String name, long count, long success, long failure, long avgMs) {}

    public record RecentExecutionItem(
            String id,
            String flowId,
            String flowName,
            String status,
            String triggeredBy,
            String startedAt,
            long durationMs
    ) {}
}
