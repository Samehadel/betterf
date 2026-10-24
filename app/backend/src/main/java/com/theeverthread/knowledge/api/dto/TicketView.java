package com.theeverthread.knowledge.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketView(
        UUID id,
        TicketContent content,
        UUID authorId,
        String authorName,
        Instant createdAt,
        String classificationStatus,
        List<String> attributes) {}
