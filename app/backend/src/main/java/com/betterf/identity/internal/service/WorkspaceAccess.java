package com.betterf.identity.internal.service;

import com.betterf.identity.api.dto.IdentityViews.*;
import com.betterf.identity.api.exception.IdentityException;
import com.betterf.identity.internal.entity.*;
import com.betterf.identity.internal.mapper.AccountMapper;
import com.betterf.identity.internal.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.*;

/** Called inside the owning use-case transaction; email locks serialize default provisioning. */
@Service
@RequiredArgsConstructor
public class WorkspaceAccess {
    private final WorkspaceRepository workspaces;
    private final MembershipRepository memberships;
    private final AccountRepository accounts;
    private final AccountMapper mapper;

    public WorkspaceEntity provisionDefault(AccountEntity account) {
        if (account.getDefaultWorkspace() != null) return account.getDefaultWorkspace();
        var workspace = createOwned(account, "My workspace");
        account.setDefaultWorkspace(workspace);
        return workspace;
    }

    public WorkspaceEntity createOwned(AccountEntity account, String name) {
        var workspace = new WorkspaceEntity();
        workspace.setId(UUID.randomUUID());
        workspace.setName(name);
        workspace.setStatus(WorkspaceStatus.ACTIVE);
        workspace = workspaces.save(workspace);
        join(account, workspace, "OWNER");
        return workspace;
    }

    public void join(AccountEntity account, WorkspaceEntity workspace, String role) {
        if (memberships
                .findByAccountIdAndWorkspaceIdAndActiveTrue(account.getId(), workspace.getId())
                .isPresent()) return;
        var membership =
                memberships
                        .findByAccountIdAndWorkspaceId(account.getId(), workspace.getId())
                        .orElseGet(
                                () -> {
                                    var created = new MembershipEntity();
                                    created.setId(UUID.randomUUID());
                                    return created;
                                });
        membership.setActive(true);
        membership.setAccount(account);
        membership.setWorkspace(workspace);
        membership.setRole(role);
        memberships.save(membership);
    }

    public AccountEntity account(String email) {
        return accounts.findByEmail(IdentityServiceImpl.normalizeEmail(email))
                .filter(AccountEntity::isActive)
                .orElseThrow(
                        () ->
                                new IdentityException(
                                        403,
                                        "ACCESS_DENIED",
                                        "Verify your email and log in to continue."));
    }

    public MembershipEntity require(String email, UUID workspaceId, boolean owner) {
        var account = account(email);
        if (workspaceId == null) throw denied();
        var member =
                memberships
                        .findByAccountIdAndWorkspaceIdAndActiveTrue(account.getId(), workspaceId)
                        .orElseThrow(WorkspaceAccess::denied);
        if (member.getWorkspace().getStatus() != WorkspaceStatus.ACTIVE
                || (owner && !"OWNER".equals(member.getRole()))) throw denied();
        return member;
    }

    public AccountView view(AccountEntity account, UUID selected) {
        var available =
                memberships
                        .findByAccountIdAndActiveTrueOrderByWorkspaceNameAsc(account.getId())
                        .stream()
                        .filter(m -> m.getWorkspace().getStatus() == WorkspaceStatus.ACTIVE)
                        .map(mapper::workspace)
                        .toList();
        UUID preferred =
                selected != null
                        ? selected
                        : account.getDefaultWorkspace() == null
                                ? null
                                : account.getDefaultWorkspace().getId();
        var active =
                available.stream().filter(w -> w.id().equals(preferred)).findFirst().orElse(null);
        if (selected != null && active == null) throw denied();
        if (active == null && !available.isEmpty()) active = available.getFirst();
        return new AccountView(
                account.getId(),
                account.getFullName(),
                account.getEmail(),
                active == null ? null : active.id(),
                active == null ? null : active.name(),
                active == null ? null : active.role(),
                available);
    }

    private static IdentityException denied() {
        return new IdentityException(
                403, "WORKSPACE_ACCESS_DENIED", "You do not have access to this workspace.");
    }
}
