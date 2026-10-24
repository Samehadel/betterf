package com.theeverthread.identity.api.dto;

/** A valid credential can describe its recipient even when acceptance is blocked. */
public record InvitationAcceptanceView(
        String status, String message, String workspaceName, String email) {}
