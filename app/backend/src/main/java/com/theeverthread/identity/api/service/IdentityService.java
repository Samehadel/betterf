package com.theeverthread.identity.api.service;

import com.theeverthread.identity.api.dto.*;
import com.theeverthread.identity.api.dto.IdentityViews.*;

import java.util.UUID;

public interface IdentityService {
    PendingView register(RegistrationRequest request);

    PendingView deliveryStatus(String email);

    PendingView resend(String email);

    VerificationView verify(UUID id, String token);

    CredentialsView credentials(String email);

    AccountView current(String email);

    AccountView current(String email, UUID workspaceId);
}
