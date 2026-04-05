package com.nexflow.nexflow_backend.controller;

import com.nexflow.nexflow_backend.model.domain.*;
import com.nexflow.nexflow_backend.service.GroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @GetMapping
    public List<UserGroup> listGroups(@AuthenticationPrincipal NexUser user) {
        return groupService.listGroups(user);
    }

    @PostMapping
    public ResponseEntity<?> createGroup(@RequestBody Map<String, Object> body,
                                          @AuthenticationPrincipal NexUser user) {
        try {
            String  name          = (String) body.getOrDefault("name", "");
            boolean allFlowAccess = Boolean.TRUE.equals(body.get("allFlowsAccess"));
            UserGroup g = groupService.createGroup(name, allFlowAccess, user);
            return ResponseEntity.ok(g);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<?> getGroup(@PathVariable UUID groupId,
                                       @AuthenticationPrincipal NexUser user) {
        try {
            return ResponseEntity.ok(groupService.getGroup(groupId, user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{groupId}")
    public ResponseEntity<?> updateGroup(@PathVariable UUID groupId,
                                          @RequestBody Map<String, Object> body,
                                          @AuthenticationPrincipal NexUser user) {
        try {
            String  name          = (String) body.get("name");
            boolean allFlowAccess = Boolean.TRUE.equals(body.get("allFlowsAccess"));
            return ResponseEntity.ok(groupService.updateGroup(groupId, name, allFlowAccess, user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{groupId}")
    public ResponseEntity<?> deleteGroup(@PathVariable UUID groupId,
                                          @AuthenticationPrincipal NexUser user) {
        try {
            groupService.deleteGroup(groupId, user);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Members ───────────────────────────────────────────────────────────────

    @GetMapping("/{groupId}/members")
    public ResponseEntity<?> listMembers(@PathVariable UUID groupId,
                                          @AuthenticationPrincipal NexUser user) {
        try {
            return ResponseEntity.ok(groupService.listMembers(groupId, user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{groupId}/members")
    public ResponseEntity<?> addMember(@PathVariable UUID groupId,
                                        @RequestBody Map<String, String> body,
                                        @AuthenticationPrincipal NexUser user) {
        try {
            UUID userId = UUID.fromString(body.get("userId"));
            return ResponseEntity.ok(groupService.addMember(groupId, userId, user));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public ResponseEntity<?> removeMember(@PathVariable UUID groupId,
                                           @PathVariable UUID userId,
                                           @AuthenticationPrincipal NexUser user) {
        try {
            groupService.removeMember(groupId, userId, user);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Flow Access ───────────────────────────────────────────────────────────

    @GetMapping("/{groupId}/flows")
    public List<FlowAccess> listFlowAccess(@PathVariable UUID groupId) {
        return groupService.listFlowAccess(null).stream()
                .filter(fa -> fa.getTargetType().equals("GROUP") && fa.getTargetId().equals(groupId))
                .toList();
    }

    @PostMapping("/{groupId}/flows/{flowId}")
    public ResponseEntity<?> grantFlowAccess(@PathVariable UUID groupId,
                                              @PathVariable UUID flowId,
                                              @AuthenticationPrincipal NexUser user) {
        try {
            FlowAccess fa = groupService.grantFlowAccess(flowId, "GROUP", groupId, user);
            return ResponseEntity.ok(fa);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{groupId}/flows/{flowId}")
    public ResponseEntity<?> revokeFlowAccess(@PathVariable UUID groupId,
                                               @PathVariable UUID flowId,
                                               @AuthenticationPrincipal NexUser user) {
        groupService.revokeFlowAccess(flowId, "GROUP", groupId);
        return ResponseEntity.noContent().build();
    }
}
