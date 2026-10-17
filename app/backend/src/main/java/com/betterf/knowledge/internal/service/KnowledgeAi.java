package com.betterf.knowledge.internal.service;

import com.betterf.knowledge.api.dto.*;

import java.util.List;

public interface KnowledgeAi {
    String refine(TicketContent content, String field);

    List<String> classify(TicketContent content);
}
