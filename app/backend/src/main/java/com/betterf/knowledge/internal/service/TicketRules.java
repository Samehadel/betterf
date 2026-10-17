package com.betterf.knowledge.internal.service;

import com.betterf.knowledge.api.dto.TicketContent;
import com.betterf.knowledge.api.exception.KnowledgeException;

import java.net.URI;
import java.util.List;
import java.util.Set;

/** Validates structured content at the use-case boundary, including non-HTTP callers. */
public final class TicketRules {
    public static final List<String> TEMPLATES =
            List.of("Knowledge", "Coding", "Problem", "Decision", "Accomplishment", "Learning");
    public static final List<String> LANGUAGES =
            List.of(
                    "plaintext",
                    "java",
                    "typescript",
                    "javascript",
                    "python",
                    "sql",
                    "bash",
                    "json",
                    "yaml",
                    "html",
                    "css",
                    "csharp",
                    "cpp",
                    "go",
                    "rust",
                    "kotlin");
    private static final Set<String> BLOCKS =
            Set.of("paragraph", "heading", "bulletList", "orderedList", "link", "code");

    private TicketRules() {}

    public static void validate(TicketContent content) {
        if (content == null) invalid("Ticket content is required.");
        text(content.title(), 200, "Title");
        text(content.capture(), 1000, "Capture");
        if (content.template() == null || !TEMPLATES.contains(content.template()))
            invalid("Choose a writing template.");
        if (content.details() == null
                || content.details().isEmpty()
                || content.details().size() > 200) invalid("Details require 1–200 blocks.");
        int length = 0;
        boolean substantive = false;
        boolean code = false;
        for (var block : content.details()) {
            if (block == null
                    || block.type() == null
                    || !BLOCKS.contains(block.type())
                    || block.text() == null) invalid("Invalid Details block.");
            length += block.text().length() + (block.url() == null ? 0 : block.url().length());
            substantive |= meaningful(block.text());
            if ("code".equals(block.type())) {
                if (block.language() == null || !LANGUAGES.contains(block.language()))
                    invalid("Choose a supported code language.");
                code |= meaningful(block.text());
            } else if (block.language() != null)
                invalid("Only code blocks can specify a language.");
            if ("link".equals(block.type())) {
                if (!meaningful(block.text()) || !safeUrl(block.url()))
                    invalid("Links require a label and an absolute HTTP(S) URL.");
            } else if (block.url() != null) invalid("Only link blocks can specify a URL.");
        }
        if (!substantive || length > 30000)
            invalid("Details must contain text and be at most 30,000 characters.");
        if ("Coding".equals(content.template()) && !code)
            invalid("Coding requires a nonempty code block.");
    }

    public static void text(String value, int max, String field) {
        if (!meaningful(value) || value.length() > max)
            invalid(field + " is required and must be at most " + max + " characters.");
    }

    public static boolean meaningful(String value) {
        return value != null
                && value.codePoints()
                        .anyMatch(
                                c ->
                                        !Character.isWhitespace(c)
                                                && !Character.isSpaceChar(c)
                                                && Character.getType(c) != Character.FORMAT);
    }

    private static boolean safeUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static void invalid(String message) {
        throw new KnowledgeException(400, "INVALID_TICKET", message);
    }
}
