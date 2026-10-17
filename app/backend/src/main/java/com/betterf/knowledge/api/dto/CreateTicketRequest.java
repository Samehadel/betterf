package com.betterf.knowledge.api.dto;

import java.util.UUID;

public record CreateTicketRequest(UUID submissionId, TicketContent content) {}
