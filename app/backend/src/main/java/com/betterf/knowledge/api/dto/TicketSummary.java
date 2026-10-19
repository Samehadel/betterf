package com.betterf.knowledge.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketSummary(
        UUID id,
        String title,
        String capturePreview,
        String detailsType,
        String authorName,
        Instant createdAt,
        String classificationStatus,
        List<String> attributes) {}
