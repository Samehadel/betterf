package com.betterf.knowledge.api.dto;

import java.util.List;

public record TicketPage(List<TicketSummary> items, String nextCursor) {}
