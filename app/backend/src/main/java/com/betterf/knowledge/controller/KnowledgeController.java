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
    public TicketView create(Principal principal, @RequestBody CreateTicketRequest request) {
        return knowledge.create(principal.getName(), request);
    }

    @GetMapping("/{id}")
    public TicketView get(Principal principal, @PathVariable UUID id) {
        return knowledge.get(principal.getName(), id);
    }

    @PostMapping("/refine")
    public RefinementView refine(Principal principal, @RequestBody RefinementRequest request) {
        return knowledge.refine(principal.getName(), request);
    }
}
