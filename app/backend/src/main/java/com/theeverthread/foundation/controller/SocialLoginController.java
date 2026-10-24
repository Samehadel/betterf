package com.theeverthread.foundation.controller;

import com.theeverthread.foundation.internal.security.SocialLoginState;
import com.theeverthread.identity.api.dto.IdentityViews.AccountView;
import com.theeverthread.identity.api.service.SocialIdentityService;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/api/auth/social")
@RequiredArgsConstructor
public class SocialLoginController {
    private final SocialIdentityService identities;
    private final ClientRegistrationRepository providers;
    private final Clock clock;

    public record ProviderView(String id, boolean available) {}

    public record LinkView(String provider) {}

    @GetMapping("/providers")
    public List<ProviderView> providers() {
        return List.of("linkedin", "facebook").stream()
                .map(id -> new ProviderView(id, providers.findByRegistrationId(id) != null))
                .toList();
    }

    @GetMapping("/pending")
    public LinkView pending(HttpServletRequest request) {
        var pending = SocialLoginState.pending(request, clock.instant());
        return new LinkView(pending == null ? null : pending.identity().provider());
    }

    @PostMapping("/link")
    public AccountView link(Authentication principal, HttpServletRequest request) {
        var identity = SocialLoginState.requireLink(request, principal.getName(), clock.instant());
        var account = identities.link(principal.getName(), identity);
        SocialLoginState.clear(request);
        return account;
    }
}
