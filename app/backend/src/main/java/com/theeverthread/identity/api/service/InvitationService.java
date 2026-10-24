package com.theeverthread.identity.api.service;

import com.theeverthread.identity.api.dto.*;

public interface InvitationService {
    java.util.List<InvitationView> sent(String administratorEmail, java.util.UUID workspaceId);

    java.util.List<ActiveMemberView> members(String administratorEmail, java.util.UUID workspaceId);

    InvitationView resend(String administratorEmail, java.util.UUID workspaceId, java.util.UUID id);

    InvitationPageView history(String administratorEmail, java.util.UUID workspaceId, int page);

    InvitationView send(
            String administratorEmail, java.util.UUID workspaceId, InvitationRequest request);

    InvitationView status(
            String administratorEmail, java.util.UUID workspaceId, InvitationRequest request);
}
