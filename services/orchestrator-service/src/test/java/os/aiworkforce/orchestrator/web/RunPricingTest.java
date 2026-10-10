// @find: tests for run pricing, run pricing, cost label, free run
// @what: Unit and integration tests (4 cases) for run pricing, for example: priced; free; unpriced; sandbox and none.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Every page reads a run's cost the same way: priced, free, sandbox, unpriced or not yet known. */
class RunPricingTest {

    @Test
    @DisplayName("a cost above zero is priced, whatever answered")
    void priced() {
        assertThat(RunPricing.of(new BigDecimal("0.0004"), new boolean[] {false, false})).isEqualTo("priced");
    }

    @Test
    @DisplayName("nothing spent on models the catalogue marks free is free, not unpriced")
    void free() {
        assertThat(RunPricing.of(BigDecimal.ZERO, new boolean[] {false, true})).isEqualTo("free");
    }

    @Test
    @DisplayName("nothing spent on a model with no price on file is unpriced, never zero")
    void unpriced() {
        assertThat(RunPricing.of(BigDecimal.ZERO, new boolean[] {false, false})).isEqualTo("unpriced");
    }

    @Test
    @DisplayName("the offline sandbox alone is sandbox; no model call yet is none")
    void sandboxAndNone() {
        assertThat(RunPricing.of(BigDecimal.ZERO, new boolean[] {true, true})).isEqualTo("sandbox");
        assertThat(RunPricing.of(BigDecimal.ZERO, null)).isEqualTo("none");
    }
}
