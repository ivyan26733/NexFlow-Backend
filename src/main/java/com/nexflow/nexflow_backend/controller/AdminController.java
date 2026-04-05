package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.FlowAccess;
import com.nexflow.nexflow_backend.model.domain.NexUser;
import com.nexflow.nexflow_backend.model.domain.UserRole;
import com.nexflow.nexflow_backend.repository.NexUserRepository;
import com.nexflow.nexflow_backend.service.GroupService;
import com.nexflow.nexflow_backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final NexUserRepository userRepository;
    private final UserService       userService;
    private final GroupService      groupService;

    // ── User management ───────────────────────────────────────────────────────

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/users")
    public List<UserSummary> listUsers() {
        return userRepository.findAll().stream().map(UserSummary::from).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/users/{userId}/role")
    public ResponseEntity<?> updateRole(@PathVariable UUID userId,
                                         @RequestBody Map<String, String> body,
                                         @AuthenticationPrincipal NexUser requester) {
        String roleName = body.get("role");
        if (roleName == null || roleName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "role is required"));
        }
        try {
            UserRole newRole = UserRole.valueOf(roleName.toUpperCase());
            NexUser updated = userService.updateRole(userId, newRole, requester);
            return ResponseEntity.ok(UserSummary.from(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Flow access ───────────────────────────────────────────────────────────

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/flows/{flowId}/access/user/{userId}")
    public ResponseEntity<?> grantUserFlowAccess(@PathVariable UUID flowId,
                                                   @PathVariable UUID userId,
                                                   @AuthenticationPrincipal NexUser requester) {
        try {
            FlowAccess fa = groupService.grantFlowAccess(flowId, "USER", userId, requester);
            return ResponseEntity.ok(fa);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/flows/{flowId}/access/user/{userId}")
    public ResponseEntity<Void> revokeUserFlowAccess(@PathVariable UUID flowId,
                                                      @PathVariable UUID userId) {
        groupService.revokeFlowAccess(flowId, "USER", userId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUB_ADMIN')")
    @GetMapping("/flows/{flowId}/access")
    public ResponseEntity<List<FlowAccess>> listFlowAccess(@PathVariable UUID flowId) {
        return ResponseEntity.ok(groupService.listFlowAccess(flowId));
    }

    // ── Safe user projection — never expose passwordHash, otpCode, googleId ──

    public record UserSummary(
            UUID     id,
            String   email,
            String   name,
            UserRole role,
            boolean  emailVerified,
            Instant  createdAt
    ) {
        static UserSummary from(NexUser u) {
            return new UserSummary(
                    u.getId(), u.getEmail(), u.getName(),
                    u.getRole(), u.isEmailVerified(), u.getCreatedAt()
            );
        }
    }
}
