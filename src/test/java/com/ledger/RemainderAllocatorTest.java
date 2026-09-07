package com.ledger;

import com.ledger.domain.RemainderAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Focused tests for the largest-remainder allocator, the mechanism that makes the
 * rounded daily interest accruals sum EXACTLY to the capitalized total.
 *
 * @author Omar Kaoud
 */
class RemainderAllocatorTest {

    @Test
    @DisplayName("ACC-001 accruals reconcile: 0.822 raw -> 0.82 total, one penny goes to Day 4")
    void acc001Reconciliation() {
        List<BigDecimal> raw = List.of(
                new BigDecimal("0.1000"),
                new BigDecimal("0.1000"),
                new BigDecimal("0.2600"),
                new BigDecimal("0.1860"),
                new BigDecimal("0.0000"),
                new BigDecimal("0.1760"));
        RemainderAllocator.Result r = RemainderAllocator.allocate(raw, 2);

        assertEquals(new BigDecimal("0.82"), r.total());
        assertEquals(List.of(
                new BigDecimal("0.10"),
                new BigDecimal("0.10"),
                new BigDecimal("0.26"),
                new BigDecimal("0.19"),  // Day 4 gets the extra penny (earliest-index tie-break)
                new BigDecimal("0.00"),
                new BigDecimal("0.17")), // Day 6 forced down so the total is exactly 0.82
                r.allocated());

        BigDecimal sum = r.allocated().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(r.total(), sum);
    }

    @Test
    @DisplayName("Allocator never drifts: sum of allocated always equals rounded raw sum")
    void neverDrifts() {
        List<BigDecimal> raw = List.of(
                new BigDecimal("0.005"),
                new BigDecimal("0.005"),
                new BigDecimal("0.005"));
        // raw sum 0.015 -> rounded at 2dp = 0.02; allocated must sum to 0.02.
        RemainderAllocator.Result r = RemainderAllocator.allocate(raw, 2);
        BigDecimal sum = r.allocated().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(r.total(), sum);
        assertEquals(new BigDecimal("0.02"), r.total());
    }
}
