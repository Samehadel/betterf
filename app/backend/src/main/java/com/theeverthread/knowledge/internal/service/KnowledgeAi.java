package com.theeverthread.knowledge.internal.service;

import com.theeverthread.knowledge.api.dto.*;

import java.util.List;

public interface KnowledgeAi {
    String refine(String original, String field);

    List<String> classify(TicketContent content);
}
