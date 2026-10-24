package com.theeverthread.knowledge.internal.service;

import com.theeverthread.knowledge.api.dto.TicketContent;

/** Keeps refinement a bounded rephrase of existing wording, never a content-generation action. */
public final class RefinementRules {
    private RefinementRules() {}

    public static String original(TicketContent content, String field) {
        String value = "title".equals(field) ? content.title() : content.capture();
        if (!TicketRules.meaningful(value))
            TicketRules.invalid("Enter wording in the selected field before refining.");
        return value.strip();
    }

    /**
     * Allow modest spelling/grammar corrections, with four extra characters for very short text.
     */
    public static int maxCharacters(String original, String field) {
        int allowance = Math.max(4, (original.length() + 4) / 5);
        return Math.min("title".equals(field) ? 200 : 1000, original.length() + allowance);
    }
}
