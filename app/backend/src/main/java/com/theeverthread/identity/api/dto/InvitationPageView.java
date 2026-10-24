package com.theeverthread.identity.api.dto;

import java.util.List;

public record InvitationPageView(List<InvitationView> invitations, boolean hasMore) {}
