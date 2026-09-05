package com.ledger.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Largest-remainder allocation.
 *
 * <p>Problem: we have a set of raw fractional amounts (e.g. daily interest
 * accruals) whose exact sum, rounded to the currency scale, is the "capitalized
 * total". If we naively round each daily amount independently and add them up,
 * the total can drift by one or more minor units from that capitalized total.
 * The non-negotiable rule requires the published per-day amounts to sum EXACTLY
 * to the capitalized total.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Compute the target total = round(sum of raw amounts) at scale.</li>
 *   <li>Floor each raw amount to scale (round toward zero at the given scale).</li>
 *   <li>The number of extra minor units to distribute = target - sum(floors),
 *       measured in minor units.</li>
 *   <li>Hand out one extra minor unit at a time to the entries with the largest
 *       fractional remainder. Ties are broken by the provided tie-break order
 *       (here: earliest index / earliest day wins).</li>
 * </ol>
 *
 * <p>This guarantees the returned amounts sum exactly to the target and each is
 * within one minor unit of its independently-rounded value.
 */
public final class RemainderAllocator {

    private RemainderAllocator() {}

    public record Result(List<BigDecimal> allocated, BigDecimal total) {}

    /**
     * @param rawAmounts the exact (unrounded) per-bucket amounts, in bucket order
     * @param scale      currency scale (2 for AED, 3 for BHD)
     * @return per-bucket rounded amounts (same order) that sum exactly to
     *         round(sum(rawAmounts)); ties in remainder are broken by earliest index.
     */
    public static Result allocate(List<BigDecimal> rawAmounts, int scale) {
        BigDecimal rawSum = BigDecimal.ZERO;
        for (BigDecimal a : rawAmounts) {
            rawSum = rawSum.add(a);
        }
        BigDecimal target = rawSum.setScale(scale, CurrencySpec.ROUNDING);

        BigDecimal minorUnit = BigDecimal.ONE.movePointLeft(scale); // e.g. 0.01 or 0.001

        // Floor each raw amount toward zero at the given scale.
        List<BigDecimal> floors = new ArrayList<>();
        BigDecimal floorSum = BigDecimal.ZERO;
        for (BigDecimal a : rawAmounts) {
            BigDecimal floor = a.setScale(scale, java.math.RoundingMode.DOWN);
            floors.add(floor);
            floorSum = floorSum.add(floor);
        }

        // How many extra minor units must be distributed to reach the target.
        BigDecimal diff = target.subtract(floorSum);
        int extraUnits = diff.movePointRight(scale).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();

        // Rank buckets by fractional remainder (raw - floor) descending, earliest index first on ties.
        record Ranked(int index, BigDecimal remainder) {}
        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < rawAmounts.size(); i++) {
            ranked.add(new Ranked(i, rawAmounts.get(i).subtract(floors.get(i))));
        }
        ranked.sort(Comparator
                .comparing((Ranked r) -> r.remainder()).reversed()
                .thenComparing(Ranked::index));

        List<BigDecimal> result = new ArrayList<>(floors);
        for (int k = 0; k < extraUnits; k++) {
            int idx = ranked.get(k).index();
            result.set(idx, result.get(idx).add(minorUnit).setScale(scale, CurrencySpec.ROUNDING));
        }

        return new Result(result, target);
    }
}
