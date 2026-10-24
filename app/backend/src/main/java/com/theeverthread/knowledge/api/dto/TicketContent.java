package com.theeverthread.knowledge.api.dto;

import java.util.List;

/** Version-one block document. Text and URLs count toward the Details limit, not JSON markup. */
public record TicketContent(
        String title, String capture, String template, List<DetailBlock> details) {
    public record DetailBlock(String type, String text, String language, String url) {}
}
