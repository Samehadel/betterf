package com.theeverthread.identity.api.service;

import com.theeverthread.identity.api.dto.*;
import com.theeverthread.identity.api.dto.IdentityViews.AccountView;

public interface InvitationAcceptanceService {
    InvitationAcceptanceView preview(InvitationLinkRequest request);

    AccountView accept(MemberRegistrationRequest request, String authenticatedEmail);
}
