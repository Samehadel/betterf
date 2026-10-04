package com.betterf.identity.api.service;

import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.dto.IdentityViews.*;

import java.util.List;
import java.util.UUID;

public interface IdentityService {
    PendingView register(RegistrationRequest request);

    PendingView resend(String email);

    VerificationView verify(UUID id, String token);

    CredentialsView credentials(String email);

    AccountView current(String email);

    List<RoleView> roles();
}
