package com.raph.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenBudgetTest {
    @Test
    void resetClearsAllSessionTokenCounters() {
        TokenBudget budget = new TokenBudget(1000);
        budget.updateCurrentConvTokens(100);
        budget.updateInjectedMemoryTokens(20);
        budget.updateCompressedHistoryTokens(30);

        budget.reset();

        assertEquals(0, budget.total());
        assertEquals(0, budget.getCurrentConvTokens());
        assertEquals(0, budget.getInjectedMemoryTokens());
        assertEquals(0, budget.getCompressedHistoryTokens());
    }
}
