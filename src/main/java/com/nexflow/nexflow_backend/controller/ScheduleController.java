package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.service.ScheduleService;
import com.nexflow.nexflow_backend.service.ScheduleService.ScheduleRequest;
import com.nexflow.nexflow_backend.service.ScheduleService.ScheduleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Cron schedules for flows. Validation errors come back as 400 {"error": "..."} (GlobalExceptionHandler). */
@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @GetMapping
    public List<ScheduleResponse> list(@AuthenticationPrincipal NexUser user) {
        return scheduleService.list(user);
    }

    @PostMapping
    public ScheduleResponse create(@RequestBody ScheduleRequest request, @AuthenticationPrincipal NexUser user) {
        return scheduleService.create(request, user);
    }

    @PutMapping("/{id}")
    public ScheduleResponse update(@PathVariable UUID id, @RequestBody ScheduleRequest request,
                                   @AuthenticationPrincipal NexUser user) {
        return scheduleService.update(id, request, user);
    }

    @PatchMapping("/{id}/toggle")
    public ScheduleResponse toggle(@PathVariable UUID id, @AuthenticationPrincipal NexUser user) {
        return scheduleService.toggle(id, user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal NexUser user) {
        scheduleService.delete(id, user);
        return ResponseEntity.noContent().build();
    }
}
