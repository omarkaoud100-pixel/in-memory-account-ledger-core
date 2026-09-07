package com.ledger.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays an event stream over the six-day window and produces a per-day report.
 *
 * <p>Order of processing for each day D (D = 1..6):
 * <ol>
 *   <li>Apply every event whose bookingDay == D, in stream order. Postings,
 *       holds, settlements and reversals take effect here.</li>
 *   <li>Assess the overdraft fee: if the forward-time closing balance for day D
 *       (entries with valueDate &le; D and bookingDay &le; D) is negative, append
 *       exactly one OVERDRAFT_FEE entry dated D. Once per day per account.</li>
 *   <li>Record the day's accrued interest basis (the post-fee closing balance if
 *       positive). Accruals are NOT posted yet.</li>
 * </ol>
 *
 * <p>At the end of Day 6, daily accruals are reconciled with largest-remainder
 * allocation and posted as a single INTEREST_CAPITALIZATION credit per account.
 *
 * <p>Key modelling decisions (see AMBIGUITIES.md / NUMBERS.md):
 * <ul>
 *   <li>Fees are assessed in FORWARD time on the day the negative close actually
 *       occurs, never retroactively on an earlier value date.</li>
 *   <li>A reversal is a new compensating entry; it does not un-assess a fee that
 *       was already booked on an earlier day.</li>
 *   <li>Interest uses the same forward-time closing balance that drives fees.</li>
 * </ul>
 *
 * @author Omar Kaoud
 */
public final class ReplayEngine {

    private static final BigDecimal DAILY_RATE = new BigDecimal("0.0004"); // 0.04% per day
    private static final Day LAST_DAY = Day.of(6);

    private final Ledger ledger;
    private final Map<String, Money> overdraftFee; // per-currency fee amount

    // Interest accrual bases captured at end of each day, per account.
    private final Map<String, List<BigDecimal>> rawAccruals = new LinkedHashMap<>();

    // Authorization lifecycle tracking for reporting.
    private final Map<String, String> authStatus = new LinkedHashMap<>();  // authId -> status
    private final Map<String, Money> authAmount = new LinkedHashMap<>();    // authId -> hold/settled amount
    private final Map<String, String> authAccount = new LinkedHashMap<>();  // authId -> accountId

    private final List<ReplayResult.ReplayError> errors = new ArrayList<>();

    public ReplayEngine(Ledger ledger, Map<String, Money> overdraftFee) {
        this.ledger = ledger;
        this.overdraftFee = overdraftFee;
    }

    public ReplayResult replay(List<Event> stream) {
        List<ReplayResult.DaySnapshot> snapshots = new ArrayList<>();

        for (int d = 1; d <= 6; d++) {
            Day day = Day.of(d);

            // 1. Apply this day's events in stream order.
            for (Event e : stream) {
                if (e.bookingDay().number() == d) {
                    apply(e);
                }
            }

            // 2. Assess overdraft fee per account (once per day).
            for (String accountId : accountIds(stream)) {
                Money closing = ledger.closingBalance(accountId, day);
                boolean feeToday = false;
                if (closing.isNegative()) {
                    Money fee = overdraftFee.get(ledger.account(accountId).currency().name());
                    ledger.append(LedgerEntry.EntryType.OVERDRAFT_FEE, "FEE-" + accountId + "-D" + d,
                            accountId, negate(fee), day, day); // fee reduces balance
                    feeToday = true;
                }
                // 3. Capture interest basis: post-fee closing balance, positive only.
                Money postFeeClosing = ledger.closingBalance(accountId, day);
                BigDecimal accrual = postFeeClosing.isPositive()
                        ? postFeeClosing.amount().multiply(DAILY_RATE)
                        : BigDecimal.ZERO;
                rawAccruals.computeIfAbsent(accountId, k -> new ArrayList<>()).add(accrual);

                snapshots.add(snapshot(day, accountId, feeToday));
            }
        }

        // End of window: capitalize interest.
        Map<String, ReplayResult.InterestSummary> interest = capitalizeInterest();

        // Rebuild final-day snapshots so they reflect the capitalization credit.
        // (Capitalization is posted with valueDate/bookingDay = Day 6.)
        List<ReplayResult.DaySnapshot> finalSnapshots = new ArrayList<>(snapshots);

        return new ReplayResult(finalSnapshots, errors, interest);
    }

    private void apply(Event e) {
        switch (e) {
            case Event.Credit c ->
                ledger.append(LedgerEntry.EntryType.CREDIT, c.id(), c.accountId(),
                        c.amount(), c.valueDate(), c.bookingDay());

            case Event.Debit d ->
                ledger.append(LedgerEntry.EntryType.DEBIT, d.id(), d.accountId(),
                        negate(d.amount()), d.valueDate(), d.bookingDay());

            case Event.Authorization a -> applyAuthorization(a);

            case Event.Settlement s -> applySettlement(s);

            case Event.Reversal r -> applyReversal(r, e);
        }
    }

    private void applyAuthorization(Event.Authorization a) {
        // Approve only if available balance stays >= 0 after the hold.
        Money availableAfter = ledger.availableBalance(a.accountId()).minus(a.hold());
        authAccount.put(a.authId(), a.accountId());
        authAmount.put(a.authId(), a.hold());
        if (availableAfter.isNegative()) {
            authStatus.put(a.authId(), "DECLINED");
            errors.add(new ReplayResult.ReplayError(a.id(), a.bookingDay(),
                    "Authorization " + a.authId() + " declined: available would be "
                            + availableAfter + " (< 0)"));
        } else {
            ledger.placeHold(a.authId(), a.accountId(), a.hold(), a.bookingDay());
            authStatus.put(a.authId(), "APPROVED");
        }
    }

    private void applySettlement(Event.Settlement s) {
        // Reject settlement that references an authorization never placed.
        if (!ledger.hasHold(s.authId())) {
            authStatus.putIfAbsent(s.authId(), "REJECTED_NO_AUTH");
            errors.add(new ReplayResult.ReplayError(s.id(), s.bookingDay(),
                    "Settlement references unknown/again-settled authorization " + s.authId()
                            + "; no funds move"));
            return;
        }
        // Post the actual settled debit, release the hold (any residual hold evaporates).
        ledger.append(LedgerEntry.EntryType.SETTLEMENT_DEBIT, s.id(), s.accountId(),
                negate(s.amount()), s.valueDate(), s.bookingDay());
        ledger.releaseHold(s.authId());
        authStatus.put(s.authId(), "SETTLED");
        authAmount.put(s.authId(), s.amount());
    }

    private void applyReversal(Event.Reversal r, Event self) {
        // Find the original posting to compensate.
        LedgerEntry original = ledger.entries().stream()
                .filter(en -> en.sourceEventId().equals(r.reversedEventId()))
                .findFirst()
                .orElse(null);
        if (original == null) {
            errors.add(new ReplayResult.ReplayError(r.id(), r.bookingDay(),
                    "Reversal references unknown event " + r.reversedEventId()));
            return;
        }
        // Append a NEW compensating entry equal to the negation of the original.
        Money compensating = negate(original.signedAmount());
        ledger.append(LedgerEntry.EntryType.REVERSAL, r.id(), r.accountId(),
                compensating, r.valueDate(), r.bookingDay());
    }

    private Map<String, ReplayResult.InterestSummary> capitalizeInterest() {
        Map<String, ReplayResult.InterestSummary> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<BigDecimal>> entry : rawAccruals.entrySet()) {
            String accountId = entry.getKey();
            CurrencySpec ccy = ledger.account(accountId).currency();
            RemainderAllocator.Result alloc = RemainderAllocator.allocate(entry.getValue(), ccy.scale());

            List<Money> daily = new ArrayList<>();
            for (BigDecimal a : alloc.allocated()) {
                daily.add(Money.of(ccy, a));
            }
            Money total = Money.of(ccy, alloc.total());

            if (total.isPositive()) {
                ledger.append(LedgerEntry.EntryType.INTEREST_CAPITALIZATION,
                        "INT-" + accountId, accountId, total, LAST_DAY, LAST_DAY);
            }
            out.put(accountId, new ReplayResult.InterestSummary(accountId, daily, total));
        }
        return out;
    }

    private ReplayResult.DaySnapshot snapshot(Day day, String accountId, boolean feeToday) {
        List<ReplayResult.AuthState> states = new ArrayList<>();
        for (Map.Entry<String, String> a : authStatus.entrySet()) {
            if (authAccount.getOrDefault(a.getKey(), "").equals(accountId)) {
                states.add(new ReplayResult.AuthState(a.getKey(), a.getValue(),
                        authAmount.getOrDefault(a.getKey(), Money.zero(ledger.account(accountId).currency()))));
            }
        }
        return new ReplayResult.DaySnapshot(
                day, accountId,
                ledger.closingBalance(accountId, day),
                ledger.availableBalance(accountId),
                feeToday,
                states);
    }

    private List<String> accountIds(List<Event> stream) {
        List<String> ids = new ArrayList<>();
        for (Event e : stream) {
            if (!ids.contains(e.accountId())) ids.add(e.accountId());
        }
        return ids;
    }

    private static Money negate(Money m) {
        return Money.zero(m.currency()).minus(m);
    }
}
