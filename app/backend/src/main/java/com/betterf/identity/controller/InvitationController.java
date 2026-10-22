package com.betterf.identity.controller;

import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.service.InvitationService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/invitations")
@RequiredArgsConstructor
public class InvitationController {
    private final InvitationService invitations;

    @GetMapping("/sent")
    public java.util.List<InvitationView> sent(
            Principal principal, @RequestHeader("X-Workspace-Id") java.util.UUID workspaceId) {
        return invitations.sent(principal.getName(), workspaceId);
    }

    @GetMapping("/members")
    public java.util.List<ActiveMemberView> members(
            Principal principal, @RequestHeader("X-Workspace-Id") java.util.UUID workspaceId) {
        return invitations.members(principal.getName(), workspaceId);
    }

    @PostMapping("/{id}/resend")
    public InvitationView resend(
            Principal principal,
            @RequestHeader("X-Workspace-Id") java.util.UUID workspaceId,
            @PathVariable java.util.UUID id) {
        return invitations.resend(principal.getName(), workspaceId, id);
    }

    @GetMapping
    public InvitationPageView history(
            Principal principal,
            @RequestHeader("X-Workspace-Id") java.util.UUID workspaceId,
            @RequestParam(defaultValue = "0") int page) {
        return invitations.history(principal.getName(), workspaceId, page);
    }

    @PostMapping
    public InvitationView send(
            Principal principal,
            @RequestHeader("X-Workspace-Id") java.util.UUID workspaceId,
            @Valid @RequestBody InvitationRequest request) {
        return invitations.send(principal.getName(), workspaceId, request);
    }

    @PostMapping("/status")
    public InvitationView status(
            Principal principal,
            @RequestHeader("X-Workspace-Id") java.util.UUID workspaceId,
            @Valid @RequestBody InvitationRequest request) {
        return invitations.status(principal.getName(), workspaceId, request);
    }
}
