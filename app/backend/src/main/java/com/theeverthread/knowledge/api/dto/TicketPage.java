package com.theeverthread.knowledge.api.dto;

import java.util.List;

public record TicketPage(List<TicketSummary> items, String nextCursor) {}
