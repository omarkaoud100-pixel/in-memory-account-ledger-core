package com.ledger.domain;

import java.util.List;
import java.util.Map;

/**
 * The full outcome of replaying the event stream: per-account, per-day snapshots
 * plus the collected errors and the interest capitalization detail.
 */
public record ReplayResult(
        List<DaySnapshot> snapshots,
        List<ReplayError> errors,
        Map<String, InterestSummary> interestByAccount) {

    /** One account's closing state at the end of one day. */
    public record DaySnapshot(
            Day day,
            String accountId,
            Money closingLedgerBalance,
            Money availableBalance,
            boolean overdraftFeeAssessedToday,
            List<AuthState> authStates) {}

    /** State of an authorization as of a given day. */
    public record AuthState(String authId, String status, Money amount) {}

    /** A rejected/failed event during replay. */
    public record ReplayError(String eventId, Day day, String reason) {}

    /** Interest detail for one account: per-day accruals and the capitalized total. */
    public record InterestSummary(
            String accountId,
            List<Money> dailyAccruals,
            Money capitalizedTotal) {}
}
