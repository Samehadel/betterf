package com.betterf.foundation.controller;

import com.betterf.foundation.api.dto.StatusView;
import com.betterf.foundation.api.service.StatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    private final StatusService service;

    public StatusController(StatusService service) { this.service = service; }

    @GetMapping(value = "/api/status", produces = "application/json")
    public StatusView status() { return service.status(); }
}
