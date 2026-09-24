package com.betterf;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class BetterfApplicationTests {
    @Test
    void moduleBoundariesAreValid() { ApplicationModules.of(BetterfApplication.class).verify(); }
}
