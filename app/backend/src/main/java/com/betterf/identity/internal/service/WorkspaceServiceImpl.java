package com.betterf.identity.internal.service;

import com.betterf.identity.api.dto.IdentityViews.AccountView;
import com.betterf.identity.api.exception.IdentityException;
import com.betterf.identity.api.service.WorkspaceService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkspaceServiceImpl implements WorkspaceService {
    private final WorkspaceAccess access;

    @Transactional(readOnly = true)
    public AccountView select(String email, UUID id) {
        return access.view(access.require(email, id, false).getAccount(), id);
    }

    @Transactional
    public AccountView create(String email, String name) {
        validate(name);
        var account = access.account(email);
        var workspace = access.createOwned(account, name.trim());
        return access.view(account, workspace.getId());
    }

    @Transactional
    public AccountView rename(String email, UUID id, String name) {
        validate(name);
        var member = access.require(email, id, true);
        member.getWorkspace().setName(name.trim());
        return access.view(member.getAccount(), id);
    }

    private void validate(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 200)
            throw new IdentityException(
                    400, "INVALID_REQUEST", "Enter a workspace name of up to 200 characters.");
    }
}
