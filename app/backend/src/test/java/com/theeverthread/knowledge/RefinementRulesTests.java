package com.theeverthread.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.theeverthread.knowledge.internal.service.RefinementRules;

import org.junit.jupiter.api.Test;

class RefinementRulesTests {
    @Test
    void lengthBudgetTracksOriginalInputAndRetainsFieldCaps() {
        assertThat(RefinementRules.maxCharacters("bug", "title")).isEqualTo(7);
        assertThat(RefinementRules.maxCharacters("x".repeat(100), "capture")).isEqualTo(120);
        assertThat(RefinementRules.maxCharacters("x".repeat(200), "title")).isEqualTo(200);
        assertThat(RefinementRules.maxCharacters("x".repeat(1000), "capture")).isEqualTo(1000);
    }
}
