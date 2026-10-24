package com.theeverthread.knowledge.api.dto;

import java.util.UUID;

public record CreateTicketRequest(UUID submissionId, TicketContent content) {}
