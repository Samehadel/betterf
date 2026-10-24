package com.theeverthread.identity.controller;

import com.theeverthread.identity.api.dto.IdentityViews.AccountView;
import com.theeverthread.identity.api.service.WorkspaceService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {
    private final WorkspaceService workspaces;

    public record NameRequest(@NotBlank @Size(max = 200) String name) {}

    @GetMapping("/{id}")
    public AccountView select(Principal principal, @PathVariable UUID id) {
        return workspaces.select(principal.getName(), id);
    }

    @PostMapping
    public AccountView create(Principal principal, @Valid @RequestBody NameRequest request) {
        return workspaces.create(principal.getName(), request.name());
    }

    @PatchMapping("/{id}")
    public AccountView rename(
            Principal principal, @PathVariable UUID id, @Valid @RequestBody NameRequest request) {
        return workspaces.rename(principal.getName(), id, request.name());
    }
}
