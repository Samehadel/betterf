package com.theeverthread.identity.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record MemberRegistrationRequest(
        @NotNull @Valid InvitationLinkRequest invitation, String fullName, String password) {}
