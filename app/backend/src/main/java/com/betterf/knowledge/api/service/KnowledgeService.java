package com.betterf.knowledge.api.service;

import com.betterf.knowledge.api.dto.*;

import java.util.UUID;

public interface KnowledgeService {
    TicketView create(String email, CreateTicketRequest request);

    TicketView get(String email, UUID id);

    RefinementView refine(String email, TicketContent content);
}
