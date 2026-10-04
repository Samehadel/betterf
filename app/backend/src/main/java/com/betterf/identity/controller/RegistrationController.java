package com.betterf.identity.controller;

import com.betterf.identity.api.dto.*;
import com.betterf.identity.api.dto.IdentityViews.*;
import com.betterf.identity.api.service.IdentityService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/registration")
public class RegistrationController {
    private final IdentityService identity;

    public RegistrationController(IdentityService identity) {
        this.identity = identity;
    }

    public record ResendRequest(@NotBlank @Email @Size(max = 254) String email) {}

    @GetMapping("/roles")
    public List<RoleView> roles() {
        return identity.roles();
    }

    @PostMapping
    public PendingView register(@Valid @RequestBody RegistrationRequest request) {
        return identity.register(request);
    }

    @PostMapping("/resend")
    public PendingView resend(@Valid @RequestBody ResendRequest request) {
        return identity.resend(request.email());
    }

    @PostMapping("/status")
    public PendingView deliveryStatus(@Valid @RequestBody ResendRequest request) {
        return identity.deliveryStatus(request.email());
    }
}
