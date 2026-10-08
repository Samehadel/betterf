package com.betterf.identity.api.service;

import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.dto.IdentityViews.AccountView;

public interface InvitationAcceptanceService {
    InvitationAcceptanceView preview(InvitationLinkRequest request);

    AccountView accept(MemberRegistrationRequest request);
}
