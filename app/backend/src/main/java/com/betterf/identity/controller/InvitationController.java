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
