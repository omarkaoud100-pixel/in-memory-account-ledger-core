package com.ledger;

import com.ledger.domain.RemainderAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ONE FAILING TEST, BY DESIGN.
 *
 * <p>WHAT THIS REVEALS
 * <p>The interest-reconciliation rule guarantees the rounded daily accruals sum
 * EXACTLY to the capitalized total, but it does not say WHICH day should absorb
 * the rounding penny when two days tie on their fractional remainder.
 *
 * <p>In our scenario Day 4 (raw 0.186) and Day 6 (raw 0.176) both have a
 * third-decimal remainder of .6 and therefore TIE. Something must break the tie.
 * We chose "earliest day wins", so the extra penny lands on Day 4, giving
 * Day 4 = 0.19 and Day 6 = 0.17.
 *
 * <p>This test encodes the OTHER equally-defensible policy: "give the penny to
 * the day with the larger interest-bearing balance". Day 4's balance (465.00) is
 * larger than Day 6's (440.00), so under a "largest base balance" tie-break the
 * penny would ALSO go to Day 4 - meaning that particular alternative does not
 * actually diverge here.
 *
 * <p>So instead we assert the "latest day wins" alternative (penny to Day 6),
 * which DOES diverge from our implementation. This test therefore FAILS, and its
 * failure is the point: it makes visible that the specification is silent on
 * tie-breaking and that a different, defensible choice produces a different
 * (still exactly-summing) allocation:
 *
 * <pre>
 *   our impl (earliest wins):  Day4 = 0.19, Day6 = 0.17
 *   this test (latest wins):   Day4 = 0.18, Day6 = 0.18
 * </pre>
 *
 * <p>Both sum to 0.82. Neither is "more correct" by the rules as written; the
 * choice is a documented judgment call (see AMBIGUITIES.md / NUMBERS.md). We keep
 * this test RED on purpose rather than delete it, as an honest marker of an
 * underspecified requirement.
 *
 * @author Omar Kaoud
 */
class FailingByDesignTest {

    @Test
    @DisplayName("FAILS BY DESIGN: exposes the undefined interest tie-break (earliest vs latest day)")
    void tieBreakIsUnderspecified() {
        List<BigDecimal> raw = List.of(
                new BigDecimal("0.1000"),
                new BigDecimal("0.1000"),
                new BigDecimal("0.2600"),
                new BigDecimal("0.1860"),  // Day 4, remainder .6
                new BigDecimal("0.0000"),
                new BigDecimal("0.1760")); // Day 6, remainder .6  -> ties with Day 4

        RemainderAllocator.Result r = RemainderAllocator.allocate(raw, 2);

        // This asserts the "latest day wins" policy. Our implementation uses
        // "earliest day wins", so Day 4 = 0.19 (not 0.18) and this assertion FAILS.
        // The failure documents that the tie-break is a judgment call, not a rule.
        assertEquals(new BigDecimal("0.18"), r.allocated().get(3),
                "expected penny on Day 6 (latest-wins); impl gives it to Day 4 (earliest-wins)");
    }
}
