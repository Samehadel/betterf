package com.betterf.knowledge.controller;

import com.betterf.knowledge.api.dto.*;
import com.betterf.knowledge.api.service.KnowledgeService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge/tickets")
@RequiredArgsConstructor
public class KnowledgeController {
    private final KnowledgeService knowledge;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView create(
            Principal principal,
            @RequestHeader("X-Workspace-Id") UUID workspaceId,
            @RequestBody CreateTicketRequest request) {
        return knowledge.create(principal.getName(), workspaceId, request);
    }

    @GetMapping
    public TicketPage browse(
            Principal principal,
            @RequestHeader("X-Workspace-Id") UUID workspaceId,
            @RequestParam(required = false) String detailsType,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return knowledge.browse(principal.getName(), workspaceId, detailsType, limit, cursor);
    }

    @GetMapping("/{id}")
    public TicketView get(
            Principal principal,
            @RequestHeader("X-Workspace-Id") UUID workspaceId,
            @PathVariable UUID id) {
        return knowledge.get(principal.getName(), workspaceId, id);
    }

    @PostMapping("/refine")
    public RefinementView refine(
            Principal principal,
            @RequestHeader("X-Workspace-Id") UUID workspaceId,
            @RequestBody RefinementRequest request) {
        return knowledge.refine(principal.getName(), workspaceId, request);
    }
}
