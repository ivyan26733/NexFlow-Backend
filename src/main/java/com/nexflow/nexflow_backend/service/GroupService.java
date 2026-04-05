package com.nexflow.nexflow_backend.service;

import com.nexflow.nexflow_backend.model.domain.*;
import com.nexflow.nexflow_backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GroupService {

    private final UserGroupRepository  groupRepository;
    private final GroupMemberRepository memberRepository;
    private final FlowAccessRepository  accessRepository;
    private final NexUserRepository     userRepository;

    // ── Groups ────────────────────────────────────────────────────────────────

    public UserGroup createGroup(String name, boolean allFlowsAccess, NexUser owner) {
        UserGroup g = new UserGroup();
        g.setName(name.trim());
        g.setOwnerId(owner.getId());
        g.setAllFlowsAccess(allFlowsAccess);
        return groupRepository.save(g);
    }

    public List<UserGroup> listGroups(NexUser requester) {
        if (requester.getRole() == UserRole.ADMIN) {
            return groupRepository.findAll();
        }
        return groupRepository.findByOwnerId(requester.getId());
    }

    public UserGroup getGroup(UUID groupId, NexUser requester) {
        UserGroup g = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));
        requireGroupAccess(g, requester);
        return g;
    }

    public UserGroup updateGroup(UUID groupId, String name, boolean allFlowsAccess, NexUser requester) {
        UserGroup g = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));
        requireGroupAccess(g, requester);
        if (name != null && !name.isBlank()) g.setName(name.trim());
        g.setAllFlowsAccess(allFlowsAccess);
        return groupRepository.save(g);
    }

    @Transactional
    public void deleteGroup(UUID groupId, NexUser requester) {
        UserGroup g = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));
        requireGroupAccess(g, requester);
        memberRepository.deleteAll(memberRepository.findByGroupId(groupId));
        accessRepository.deleteAll(accessRepository.findByTargetTypeAndTargetId("GROUP", groupId));
        groupRepository.delete(g);
    }

    // ── Members ───────────────────────────────────────────────────────────────

    public GroupMember addMember(UUID groupId, UUID userId, NexUser requester) {
        UserGroup g = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));
        requireGroupAccess(g, requester);
        userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (memberRepository.findByGroupIdAndUserId(groupId, userId).isPresent()) {
            throw new IllegalArgumentException("User is already a member of this group");
        }

        GroupMember m = new GroupMember();
        m.setGroupId(groupId);
        m.setUserId(userId);
        return memberRepository.save(m);
    }

    @Transactional
    public void removeMember(UUID groupId, UUID userId, NexUser requester) {
        UserGroup g = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));
        requireGroupAccess(g, requester);
        memberRepository.deleteByGroupIdAndUserId(groupId, userId);
    }

    public List<NexUser> listMembers(UUID groupId, NexUser requester) {
        UserGroup g = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));
        requireGroupAccess(g, requester);
        return memberRepository.findByGroupId(groupId).stream()
                .map(m -> userRepository.findById(m.getUserId()).orElse(null))
                .filter(u -> u != null)
                .toList();
    }

    // ── Flow Access ───────────────────────────────────────────────────────────

    public FlowAccess grantFlowAccess(UUID flowId, String targetType, UUID targetId, NexUser requester) {
        if (!"USER".equals(targetType) && !"GROUP".equals(targetType)) {
            throw new IllegalArgumentException("targetType must be USER or GROUP");
        }
        if (accessRepository.findByFlowIdAndTargetTypeAndTargetId(flowId, targetType, targetId).isPresent()) {
            throw new IllegalArgumentException("Access already granted");
        }
        FlowAccess fa = new FlowAccess();
        fa.setFlowId(flowId);
        fa.setTargetType(targetType);
        fa.setTargetId(targetId);
        fa.setGrantedBy(requester.getId());
        return accessRepository.save(fa);
    }

    @Transactional
    public void revokeFlowAccess(UUID flowId, String targetType, UUID targetId) {
        accessRepository.deleteByFlowIdAndTargetTypeAndTargetId(flowId, targetType, targetId);
    }

    public List<FlowAccess> listFlowAccess(UUID flowId) {
        return accessRepository.findByFlowId(flowId);
    }

    // ── Access check helpers ──────────────────────────────────────────────────

    /**
     * Returns true if the given user has access to the given flow.
     * ADMIN always has access.
     */
    public boolean hasFlowAccess(UUID flowId, UUID ownerId, NexUser user) {
        if (user.getRole() == UserRole.ADMIN) return true;
        // Flows with no owner (created before auth) are accessible to all logged-in users
        if (ownerId == null) return true;
        if (user.getId().equals(ownerId)) return true;

        // Direct user access
        if (accessRepository.findByFlowIdAndTargetTypeAndTargetId(flowId, "USER", user.getId()).isPresent()) {
            return true;
        }

        // Group access
        List<UUID> groupIds = memberRepository.findByUserId(user.getId())
                .stream().map(GroupMember::getGroupId).toList();

        for (UUID gid : groupIds) {
            UserGroup g = groupRepository.findById(gid).orElse(null);
            if (g == null) continue;
            if (g.isAllFlowsAccess()) return true;
            if (accessRepository.findByFlowIdAndTargetTypeAndTargetId(flowId, "GROUP", gid).isPresent()) {
                return true;
            }
        }

        return false;
    }

    private void requireGroupAccess(UserGroup g, NexUser requester) {
        if (requester.getRole() == UserRole.ADMIN) return;
        if (!g.getOwnerId().equals(requester.getId())) {
            throw new IllegalArgumentException("Access denied");
        }
    }
}
