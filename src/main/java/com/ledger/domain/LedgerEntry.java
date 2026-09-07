package com.ledger.domain;

/**
 * An immutable, append-only posting to the ledger. Once created it is never
 * mutated or removed. A reversal or fee is represented by appending a NEW entry,
 * never by editing an existing one.
 *
 * <p>{@code signedAmount} is the effect on the ledger balance: credits and
 * reversals-of-debits are positive, debits and fees are negative.
 *
 * <p>{@code valueDate} determines which day's closing balance this entry falls
 * into; {@code bookingDay} records when it was actually appended (forward time).
 *
 * @author Omar Kaoud
 */
public record LedgerEntry(
        String entryId,
        String sourceEventId,
        EntryType type,
        String accountId,
        Money signedAmount,
        Day valueDate,
        Day bookingDay) {

    public enum EntryType {
        CREDIT,
        DEBIT,
        SETTLEMENT_DEBIT,
        REVERSAL,
        OVERDRAFT_FEE,
        INTEREST_CAPITALIZATION
    }
}
