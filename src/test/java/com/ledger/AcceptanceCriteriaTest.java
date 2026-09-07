package com.ledger;

import com.ledger.app.Scenario;
import com.ledger.domain.CurrencySpec;
import com.ledger.domain.Day;
import com.ledger.domain.Ledger;
import com.ledger.domain.Money;
import com.ledger.domain.ReplayEngine;
import com.ledger.domain.ReplayResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the correct interpretation of every acceptance criterion. Each test
 * either confirms a criterion we accept, or demonstrates the true behaviour that
 * refutes a criterion we reject (see REJECTED.md). Numbers here are the ones a
 * human can and must reproduce by hand in the live defense.
 *
 * @author Omar Kaoud
 */
class AcceptanceCriteriaTest {

    private Ledger ledger;
    private ReplayResult result;

    @BeforeEach
    void replay() {
        ledger = new Ledger();
        ledger.registerAccount(Scenario.ACC_001);
        ledger.registerAccount(Scenario.ACC_002);
        Map<String, Money> fee = Map.of(
                "AED", Money.of(CurrencySpec.AED, "25.00"),
                "BHD", Money.of(CurrencySpec.BHD, "25.000"));
        result = new ReplayEngine(ledger, fee).replay(Scenario.eventStream());
    }

    private ReplayResult.DaySnapshot snap(String acc, int day) {
        return result.snapshots().stream()
                .filter(s -> s.accountId().equals(acc) && s.day().number() == day)
                .findFirst().orElseThrow();
    }

    // ---- Criterion 1: ACCEPTED (correct) ----------------------------------
    // "Day 2 closing ledger balance, evaluated at end of Day 5, before any fee,
    //  is AED -370.00." This is the value-dated slice (value_date <= Day 2) taken
    //  across everything booked through Day 5: E1 +1200, E2 -950, E7 -620 = -370.
    @Test
    @DisplayName("C1 ACCEPT: Day-2 balance evaluated at end of Day 5 is AED -370.00")
    void day2ValueDatedSliceAsOfDay5IsMinus370() {
        // Value date <= Day 2, as-of end of Day 5: includes E7 (booked Day 5,
        // value_date Day 2) but NOT E9 (reversal, booked Day 6).
        Money slice = ledger.valueDatedSliceAsOf("ACC-001", Day.of(2), Day.of(5));
        assertEquals(new BigDecimal("-370.00"), slice.amount());
    }

    // And once E9 is booked (end of Day 6), that same value date nets back to +250.00,
    // because the reversal (value_date Day 2) cancels E7 within the Day-2 slice.
    @Test
    @DisplayName("C1 context: Day-2 slice after E9 (end of Day 6) nets back to AED 250.00")
    void day2ValueDatedSliceAfterReversalIs250() {
        Money slice = ledger.valueDatedSliceAsOf("ACC-001", Day.of(2), Day.of(6));
        assertEquals(new BigDecimal("250.00"), slice.amount());
    }

    // Companion: the FORWARD-TIME Day-2 closing (what actually closed on Day 2,
    // when E7 did not yet exist) is +250.00. Two different questions, both correct.
    @Test
    @DisplayName("C1 context: forward-time Day-2 closing is AED 250.00 (E7 not yet booked)")
    void day2ForwardTimeClosingIs250() {
        assertEquals(new BigDecimal("250.00"), snap("ACC-001", 2).closingLedgerBalance().amount());
    }

    // ---- Criterion 2: REJECTED --------------------------------------------
    // "E7 causes exactly one overdraft fee, on Day 2." The single fee is correct,
    // but it is assessed on Day 5 (the day the negative close occurs in forward
    // time), NOT retroactively on Day 2.
    @Test
    @DisplayName("C2 REJECT: the one overdraft fee is on Day 5, not Day 2")
    void singleOverdraftFeeIsOnDay5NotDay2() {
        assertFalse(snap("ACC-001", 2).overdraftFeeAssessedToday(), "no fee on Day 2");
        assertTrue(snap("ACC-001", 5).overdraftFeeAssessedToday(), "fee on Day 5");

        long feeCount = ledger.entries().stream()
                .filter(e -> e.type() == com.ledger.domain.LedgerEntry.EntryType.OVERDRAFT_FEE)
                .count();
        assertEquals(1, feeCount, "exactly one overdraft fee across the window");
    }

    // ---- Criterion 3: ACCEPTED --------------------------------------------
    // "The Day 4 settlement of Auth-A must be accepted."
    @Test
    @DisplayName("C3 ACCEPT: Auth-A settles on Day 4 and posts -185.00")
    void authASettlementAccepted() {
        boolean settled = snap("ACC-001", 4).authStates().stream()
                .anyMatch(a -> a.authId().equals("Auth-A") && a.status().equals("SETTLED"));
        assertTrue(settled, "Auth-A must be SETTLED on Day 4");
        // Day 4 closing = 250 + 400 - 185 = 465.00
        assertEquals(new BigDecimal("465.00"), snap("ACC-001", 4).closingLedgerBalance().amount());
    }

    // ---- Criterion 4: ACCEPTED --------------------------------------------
    // "Settlement referencing an authorization id not present must be rejected and
    //  funds must not leave the account." E6 settles Auth-Z which was never authorized.
    @Test
    @DisplayName("C4 ACCEPT: E6 (Auth-Z, no prior auth) is rejected and no funds move")
    void unknownAuthSettlementRejected() {
        boolean hasE6Error = result.errors().stream().anyMatch(e -> e.eventId().equals("E6"));
        assertTrue(hasE6Error, "E6 must be recorded as a rejection");
        // No SETTLEMENT_DEBIT entry may exist for E6.
        boolean anyE6Posting = ledger.entries().stream().anyMatch(e -> e.sourceEventId().equals("E6"));
        assertFalse(anyE6Posting, "E6 must not post any ledger entry");
    }

    // ---- Criterion 5: PARTIALLY REJECTED ----------------------------------
    // "If Auth-B is approved, its hold reduces available but not ledger." The rule
    // itself is sound, but its premise is false here: Auth-B is DECLINED because
    // available balance is already negative on Day 5, so no hold is ever placed.
    @Test
    @DisplayName("C5 REJECT premise: Auth-B is DECLINED (available < 0), so no hold applies")
    void authBIsDeclined() {
        boolean declined = snap("ACC-001", 5).authStates().stream()
                .anyMatch(a -> a.authId().equals("Auth-B") && a.status().equals("DECLINED"));
        assertTrue(declined, "Auth-B must be DECLINED");
    }

    // ---- Criterion 6: REJECTED --------------------------------------------
    // "After E9, all balances AND fees return to pre-E7 values." Balances net back
    // (ledger returns to +465 pre-interest), but the Day-5 overdraft fee is
    // append-only and is NOT reversed, so fees do NOT return to pre-E7 state.
    @Test
    @DisplayName("C6 REJECT: after E9 the Day-5 fee persists (append-only), fees do not reset")
    void reversalDoesNotUndoFee() {
        long feeCount = ledger.entries().stream()
                .filter(e -> e.type() == com.ledger.domain.LedgerEntry.EntryType.OVERDRAFT_FEE)
                .count();
        assertEquals(1, feeCount, "the Day-5 fee remains after the Day-6 reversal");
        // Day-6 closing = 1200 - 950 + 400 - 185 - 620 + 620 - 25(fee) = 440.00
        assertEquals(new BigDecimal("440.00"), snap("ACC-001", 6).closingLedgerBalance().amount());
    }

    // ---- Criterion 7: REJECTED --------------------------------------------
    // "The three BHD instalments must each be BHD 3.334." 3.334*3 = 10.002 != 10.000.
    // Correct equal-as-possible split summing to exactly 10.000: 3.334 + 3.333 + 3.333.
    @Test
    @DisplayName("C7 REJECT: BHD instalments sum to exactly 10.000, not 3.334 each")
    void bhdInstalmentsSumExactly() {
        assertEquals(new BigDecimal("10.000"), snap("ACC-002", 5).closingLedgerBalance().amount());
    }

    // ---- Criterion 8: REJECTED --------------------------------------------
    // "If rounded daily accruals don't sum to the capitalized total, discard the
    //  remainder." Contradicts the non-negotiable exact-sum rule; we use
    //  largest-remainder allocation so the daily figures sum EXACTLY to the total.
    @Test
    @DisplayName("C8 REJECT: daily accruals sum EXACTLY to capitalized total (no discard)")
    void interestAccrualsReconcileExactly() {
        ReplayResult.InterestSummary is = result.interestByAccount().get("ACC-001");
        BigDecimal sum = is.dailyAccruals().stream()
                .map(Money::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(is.capitalizedTotal().amount(), sum, "daily accruals must reconcile to the total");
        assertEquals(new BigDecimal("0.82"), is.capitalizedTotal().amount());
    }

    // ---- Final balances ----------------------------------------------------
    @Test
    @DisplayName("Final ledger balances include the capitalized interest credit")
    void finalBalances() {
        assertEquals(new BigDecimal("440.82"), ledger.ledgerBalance("ACC-001").amount());
        assertEquals(new BigDecimal("10.008"), ledger.ledgerBalance("ACC-002").amount());
    }
}
