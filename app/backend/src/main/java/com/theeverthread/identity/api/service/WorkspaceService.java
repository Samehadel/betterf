package com.theeverthread.identity.api.service;

import com.theeverthread.identity.api.dto.IdentityViews.AccountView;

import java.util.UUID;

public interface WorkspaceService {
    AccountView select(String email, UUID id);

    AccountView create(String email, String name);

    AccountView rename(String email, UUID id, String name);
}
