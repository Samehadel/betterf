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
    public java.util.List<InvitationView> sent(Principal principal) {
        return invitations.sent(principal.getName());
    }

    @GetMapping("/members")
    public java.util.List<ActiveMemberView> members(Principal principal) {
        return invitations.members(principal.getName());
    }

    @PostMapping("/{id}/resend")
    public InvitationView resend(Principal principal, @PathVariable java.util.UUID id) {
        return invitations.resend(principal.getName(), id);
    }

    @GetMapping
    public InvitationPageView history(
            Principal principal, @RequestParam(defaultValue = "0") int page) {
        return invitations.history(principal.getName(), page);
    }

    @PostMapping
    public InvitationView send(Principal principal, @Valid @RequestBody InvitationRequest request) {
        return invitations.send(principal.getName(), request);
    }

    @PostMapping("/status")
    public InvitationView status(
            Principal principal, @Valid @RequestBody InvitationRequest request) {
        return invitations.status(principal.getName(), request);
    }
}
