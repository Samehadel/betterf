package com.betterf.knowledge.api.service;

import com.betterf.knowledge.api.dto.*;

import java.util.UUID;

public interface KnowledgeService {
    TicketView create(String email, UUID workspaceId, CreateTicketRequest request);

    TicketPage browse(
            String email, UUID workspaceId, String detailsType, Integer limit, String cursor);

    TicketView get(String email, UUID workspaceId, UUID id);

    RefinementView refine(String email, UUID workspaceId, RefinementRequest request);
}
