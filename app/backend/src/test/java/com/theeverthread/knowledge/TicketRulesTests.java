package com.theeverthread.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.theeverthread.knowledge.api.dto.TicketContent;
import com.theeverthread.knowledge.api.dto.TicketContent.DetailBlock;
import com.theeverthread.knowledge.internal.service.TicketRules;

import org.junit.jupiter.api.Test;

import java.util.List;

class TicketRulesTests {
    TicketContent content(String title, String capture, String template, DetailBlock... blocks) {
        return new TicketContent(title, capture, template, List.of(blocks));
    }

    DetailBlock text(String value) {
        return new DetailBlock("paragraph", value, null, null);
    }

    @Test
    void rejectsBlankFormattingOnlyAndOverLimitContent() {
        for (var blank : List.of("", " \t\n", "\u00a0\u200b")) {
            assertThatThrownBy(
                            () ->
                                    TicketRules.validate(
                                            content(
                                                    blank,
                                                    "Capture",
                                                    "Knowledge",
                                                    text("Details"))))
                    .hasMessageContaining("Title");
            TicketRules.validate(content("Title", blank, "Knowledge", text("Details")));
            assertThatThrownBy(
                            () ->
                                    TicketRules.validate(
                                            content("Title", "Capture", "Knowledge", text(blank))))
                    .hasMessageContaining("Details");
        }
        assertThatThrownBy(
                        () ->
                                TicketRules.validate(
                                        content(
                                                "x".repeat(201),
                                                "Capture",
                                                "Knowledge",
                                                text("Details"))))
                .hasMessageContaining("200");
        assertThatThrownBy(
                        () ->
                                TicketRules.validate(
                                        content(
                                                "Title",
                                                "x".repeat(1001),
                                                "Knowledge",
                                                text("Details"))))
                .hasMessageContaining("1000");
        assertThatThrownBy(
                        () ->
                                TicketRules.validate(
                                        content(
                                                "Title",
                                                "Capture",
                                                "Knowledge",
                                                text("x".repeat(30001)))))
                .hasMessageContaining("30,000");
        TicketRules.validate(
                content("x".repeat(200), "x".repeat(1000), "Knowledge", text("x".repeat(30000))));
    }

    @Test
    void codingRequiresRealCodeAndLinksRejectExecutableUrls() {
        assertThatThrownBy(
                        () ->
                                TicketRules.validate(
                                        content(
                                                "Title",
                                                "Capture",
                                                "Coding",
                                                text("Some context"))))
                .hasMessageContaining("code block");
        assertThatThrownBy(
                        () ->
                                TicketRules.validate(
                                        content(
                                                "Title",
                                                "Capture",
                                                "Coding",
                                                new DetailBlock("code", " ", "java", null))))
                .hasMessageContaining("Details");
        assertThatThrownBy(
                        () ->
                                TicketRules.validate(
                                        content(
                                                "Title",
                                                "Capture",
                                                "Knowledge",
                                                new DetailBlock(
                                                        "link",
                                                        "Click",
                                                        null,
                                                        "javascript:alert(1)"))))
                .hasMessageContaining("HTTP(S)");
        TicketRules.validate(
                content(
                        "Title",
                        "Capture",
                        "Coding",
                        new DetailBlock("code", "print('ok')", "python", null),
                        new DetailBlock("link", "Docs", null, "https://example.com")));
    }

    @Test
    void malformedBlockMetadataIsRejectedAsValidationRatherThanServerErrors() {
        assertThatThrownBy(
                        () ->
                                TicketRules.validate(
                                        content("Title", "Capture", null, text("Details"))))
                .hasMessageContaining("template");
        assertThatThrownBy(
                        () ->
                                TicketRules.validate(
                                        content(
                                                "Title",
                                                "Capture",
                                                "Knowledge",
                                                new DetailBlock(null, "Details", null, null))))
                .hasMessageContaining("Invalid Details");
        assertThatThrownBy(
                        () ->
                                TicketRules.validate(
                                        content(
                                                "Title",
                                                "Capture",
                                                "Coding",
                                                new DetailBlock(
                                                        "code", "return key;", null, null))))
                .hasMessageContaining("language");
    }

    @Test
    void omittedCaptureIsOptionalAndStillBoundedWhenProvided() {
        TicketRules.validate(content("Title", null, "Knowledge", text("Details")));
        TicketRules.validate(content("Title", "", "Knowledge", text("Details")));
        assertThatThrownBy(
                        () ->
                                TicketRules.validate(
                                        content(
                                                "Title",
                                                "x".repeat(1001),
                                                "Knowledge",
                                                text("Details"))))
                .hasMessageContaining("1000");
    }
}
