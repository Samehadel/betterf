package com.betterf.knowledge.api.dto;

/** Only existing Title or Capture wording may be rephrased; Details are not sent for refinement. */
public record RefinementRequest(String field, TicketContent content) {}
