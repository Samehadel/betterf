package com.betterf.identity.api.dto;

import java.util.List;

public record InvitationPageView(List<InvitationView> invitations, boolean hasMore) {}
