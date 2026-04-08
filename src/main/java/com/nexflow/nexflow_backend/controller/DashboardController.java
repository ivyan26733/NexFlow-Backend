package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.service.DashboardSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardSummaryService dashboardSummaryService;

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryService.DashboardSummary> getSummary(
            @AuthenticationPrincipal NexUser user,
            @RequestParam(defaultValue = "false") boolean refresh
    ) {
        if (user == null) return ResponseEntity.status(401).build();
        log.debug("[Dashboard] summary request userId={} refresh={}", user.getId(), refresh);
        return ResponseEntity.ok(dashboardSummaryService.getSummary(user, refresh));
    }
}
