package com.betterf.knowledge.api.dto;

/** Only Title or Capture may be refined; complete Details remain context. */
public record RefinementRequest(String field, TicketContent content) {}
