package com.theeverthread;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class TheeverthreadApplicationTests {
    @Test
    void moduleBoundariesAreValid() { ApplicationModules.of(TheeverthreadApplication.class).verify(); }
}
