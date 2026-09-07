package com.ledger.domain;

/**
 * The input event stream. Events are immutable and are replayed in stream order.
 *
 * <p>Every event carries:
 * <ul>
 *   <li>{@code id} - stable identifier (E1..E10),</li>
 *   <li>{@code bookingDay} - the day the event is processed/appended (forward time),</li>
 *   <li>{@code accountId} - the target account.</li>
 * </ul>
 *
 * <p>The sealed hierarchy makes the set of event kinds exhaustive, so the replay
 * engine's switch is checked by the compiler - a new event type cannot be added
 * without the engine being forced to handle it.
 *
 * @author Omar Kaoud
 */
public sealed interface Event
        permits Event.Credit, Event.Debit, Event.Authorization,
                Event.Settlement, Event.Reversal {

    String id();

    Day bookingDay();

    String accountId();

    /** A credit posts a positive amount to the ledger, dated by valueDate. */
    record Credit(String id, Day bookingDay, String accountId, Money amount, Day valueDate)
            implements Event {}

    /** A debit posts a negative amount to the ledger, dated by valueDate. */
    record Debit(String id, Day bookingDay, String accountId, Money amount, Day valueDate)
            implements Event {}

    /**
     * An authorization places a hold. It does NOT touch the ledger balance.
     * valueDate is carried for completeness but a hold affects available balance
     * from its booking day.
     */
    record Authorization(String id, Day bookingDay, String accountId, String authId,
                         Money hold, Day valueDate) implements Event {}

    /**
     * A settlement finalises a prior authorization: it posts an actual debit for
     * the settled amount (dated by valueDate) and releases the referenced hold.
     * If the referenced authId was never authorized, the settlement is rejected
     * and nothing posts.
     */
    record Settlement(String id, Day bookingDay, String accountId, String authId,
                      Money amount, Day valueDate) implements Event {}

    /**
     * A reversal posts a compensating entry that negates a prior posting event.
     * It is a NEW append-only entry, not a deletion. valueDate mirrors the
     * reversed event's value date.
     */
    record Reversal(String id, Day bookingDay, String accountId, String reversedEventId,
                    Day valueDate) implements Event {}
}
