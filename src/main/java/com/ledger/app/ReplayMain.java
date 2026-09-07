package com.ledger.app;

import com.ledger.domain.CurrencySpec;
import com.ledger.domain.Day;
import com.ledger.domain.Ledger;
import com.ledger.domain.Money;
import com.ledger.domain.ReplayEngine;
import com.ledger.domain.ReplayResult;

import java.util.List;
import java.util.Map;

/**
 * Runnable harness. Replays the scenario and prints, per day, the closing ledger
 * balance, whether an overdraft fee was assessed, authorization states, and any
 * errors, followed by the interest capitalization detail.
 *
 * @author Omar Kaoud
 */
public final class ReplayMain {

    public static void main(String[] args) {
        Ledger ledger = new Ledger();
        ledger.registerAccount(Scenario.ACC_001);
        ledger.registerAccount(Scenario.ACC_002);

        Map<String, Money> overdraftFee = Map.of(
                "AED", Money.of(CurrencySpec.AED, "25.00"),
                "BHD", Money.of(CurrencySpec.BHD, "25.000"));

        ReplayEngine engine = new ReplayEngine(ledger, overdraftFee);
        ReplayResult result = engine.replay(Scenario.eventStream());

        System.out.println("=== IN-MEMORY ACCOUNT LEDGER CORE - SIX-DAY REPLAY ===");
        System.out.println();

        for (int d = 1; d <= 6; d++) {
            final int dd = d;
            Day day = Day.of(d);
            System.out.println("---- " + day + " ----");
            for (ReplayResult.DaySnapshot s : result.snapshots()) {
                if (s.day().number() != d) continue;
                System.out.printf("  %s%n", s.accountId());
                System.out.printf("    closing ledger balance : %s%n", s.closingLedgerBalance());
                System.out.printf("    available balance      : %s%n", s.availableBalance());
                System.out.printf("    overdraft fee today    : %s%n", s.overdraftFeeAssessedToday() ? "YES (25)" : "no");
                if (!s.authStates().isEmpty()) {
                    System.out.print("    authorizations         : ");
                    for (ReplayResult.AuthState a : s.authStates()) {
                        System.out.printf("[%s=%s %s] ", a.authId(), a.status(), a.amount());
                    }
                    System.out.println();
                }
            }
            List<ReplayResult.ReplayError> dayErrors = result.errors().stream()
                    .filter(e -> e.day().number() == dd).toList();
            for (ReplayResult.ReplayError e : dayErrors) {
                System.out.printf("    ERROR %s: %s%n", e.eventId(), e.reason());
            }
            System.out.println();
        }

        System.out.println("---- INTEREST CAPITALIZATION (end of Day 6) ----");
        for (var e : result.interestByAccount().entrySet()) {
            ReplayResult.InterestSummary is = e.getValue();
            System.out.printf("  %s daily accruals: ", is.accountId());
            for (Money m : is.dailyAccruals()) {
                System.out.print(m.amount().toPlainString() + " ");
            }
            System.out.println();
            System.out.printf("    capitalized total: %s%n", is.capitalizedTotal());
        }

        System.out.println();
        System.out.println("---- FINAL LEDGER BALANCES (incl. capitalized interest) ----");
        System.out.printf("  ACC-001: %s%n", ledger.ledgerBalance("ACC-001"));
        System.out.printf("  ACC-002: %s%n", ledger.ledgerBalance("ACC-002"));
    }
}
