package com.betterf.knowledge.internal.service;

import com.betterf.knowledge.api.dto.*;

import java.util.List;

public interface KnowledgeAi {
    RefinementView refine(TicketContent content);

    List<String> classify(TicketContent content);
}
