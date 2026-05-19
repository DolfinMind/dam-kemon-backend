package com.damKemon.dam.kemon;

import org.junit.jupiter.api.Test;

/**
 * Smoke marker. The full Spring context boot requires a live MongoDB +
 * mail server, so we keep the wire-up tests in component-scoped slices
 * (WebMvcTest, plain JUnit, etc) rather than {@code @SpringBootTest}.
 */
class DamKemonApplicationTests {

    @Test
    void packageStructureSmokeCheck() {
        // Confirms the test infrastructure compiles + runs. Real coverage
        // lives in the focused slice tests next to this file.
    }
}
