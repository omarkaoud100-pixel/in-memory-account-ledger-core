package com.ledger.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-memory ledger core.
 *
 * <p>Holds the append-only entry log for all accounts, the set of active holds,
 * and the accounts themselves. It exposes exactly the queries the fee/interest
 * logic and the acceptance criteria need:
 * <ul>
 *   <li>{@link #closingBalance(String, Day)} - sum of signed entries with
 *       valueDate &le; the given day that have ALREADY been appended. This is the
 *       forward-time closing balance used for fees and interest.</li>
 *   <li>{@link #valueDatedSlice(String, Day)} - same value-date filter but over the
 *       WHOLE log regardless of when entries were appended. Used to answer questions
 *       of the form "the Day 2 balance evaluated at end of Day 5".</li>
 *   <li>{@link #availableBalance(String)} - ledger balance across all appended
 *       entries minus active holds. Drives authorization decisions.</li>
 * </ul>
 *
 * <p>Append-only invariant: entries are only ever added to {@code entries}; no
 * method removes or mutates an existing entry.
 *
 * @author Omar Kaoud
 */
public final class Ledger {

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final List<LedgerEntry> entries = new ArrayList<>();

    /** Active holds keyed by authorization id. A settled/released hold is removed. */
    private final Map<String, Hold> activeHolds = new LinkedHashMap<>();

    private int entrySeq = 0;

    public record Hold(String authId, String accountId, Money amount, Day placedOn) {}

    public void registerAccount(Account account) {
        accounts.put(account.id(), account);
    }

    public Account account(String id) {
        Account a = accounts.get(id);
        if (a == null) {
            throw new IllegalArgumentException("Unknown account: " + id);
        }
        return a;
    }

    public List<LedgerEntry> entries() {
        return List.copyOf(entries);
    }

    // ---- append-only writes -------------------------------------------------

    public LedgerEntry append(LedgerEntry.EntryType type, String sourceEventId,
                              String accountId, Money signedAmount, Day valueDate, Day bookingDay) {
        LedgerEntry entry = new LedgerEntry(
                "L" + (++entrySeq), sourceEventId, type, accountId, signedAmount, valueDate, bookingDay);
        entries.add(entry);
        return entry;
    }

    public void placeHold(String authId, String accountId, Money amount, Day placedOn) {
        activeHolds.put(authId, new Hold(authId, accountId, amount, placedOn));
    }

    /** Returns the released hold, or null if the authId is unknown. */
    public Hold releaseHold(String authId) {
        return activeHolds.remove(authId);
    }

    public boolean hasHold(String authId) {
        return activeHolds.containsKey(authId);
    }

    // ---- balance queries ----------------------------------------------------

    /**
     * Forward-time closing balance for {@code day}: sum of signed amounts of all
     * entries whose valueDate &le; day AND whose bookingDay &le; day. An entry can
     * only affect a day on or after it was actually appended.
     */
    public Money closingBalance(String accountId, Day day) {
        CurrencySpec ccy = account(accountId).currency();
        BigDecimal sum = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            if (!e.accountId().equals(accountId)) continue;
            if (e.valueDate().onOrBefore(day) && e.bookingDay().onOrBefore(day)) {
                sum = sum.add(e.signedAmount().amount());
            }
        }
        return Money.of(ccy, ccy.round(sum));
    }

    /**
     * Value-dated slice ignoring booking day: sum of all entries (across the whole
     * log, whenever appended) whose valueDate &le; day. This is the "final" view of
     * a value date once every event in the window has been booked.
     */
    public Money valueDatedSlice(String accountId, Day day) {
        CurrencySpec ccy = account(accountId).currency();
        BigDecimal sum = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            if (!e.accountId().equals(accountId)) continue;
            if (e.valueDate().onOrBefore(day)) {
                sum = sum.add(e.signedAmount().amount());
            }
        }
        return Money.of(ccy, ccy.round(sum));
    }

    /**
     * As-of value-dated slice: the balance of a value date {@code valueDay} as it
     * would have been reported at the end of booking day {@code asOf}. Sums entries
     * whose valueDate &le; valueDay AND whose bookingDay &le; asOf.
     *
     * <p>This is the query that answers "the Day 2 closing balance, evaluated at end
     * of Day 5": valueDay = Day 2, asOf = Day 5. At that point E7 (booked Day 5,
     * value_date Day 2) is included but E9 (the reversal, booked Day 6) is not, so
     * the result is 1200 - 950 - 620 = -370.00.
     */
    public Money valueDatedSliceAsOf(String accountId, Day valueDay, Day asOf) {
        CurrencySpec ccy = account(accountId).currency();
        BigDecimal sum = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            if (!e.accountId().equals(accountId)) continue;
            if (e.valueDate().onOrBefore(valueDay) && e.bookingDay().onOrBefore(asOf)) {
                sum = sum.add(e.signedAmount().amount());
            }
        }
        return Money.of(ccy, ccy.round(sum));
    }

    /** Total posted ledger balance across all appended entries (no date filter). */
    public Money ledgerBalance(String accountId) {
        CurrencySpec ccy = account(accountId).currency();
        BigDecimal sum = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            if (e.accountId().equals(accountId)) {
                sum = sum.add(e.signedAmount().amount());
            }
        }
        return Money.of(ccy, ccy.round(sum));
    }

    /** Available balance = ledger balance minus the sum of active holds. */
    public Money availableBalance(String accountId) {
        Money ledger = ledgerBalance(accountId);
        CurrencySpec ccy = account(accountId).currency();
        BigDecimal holds = BigDecimal.ZERO;
        for (Hold h : activeHolds.values()) {
            if (h.accountId().equals(accountId)) {
                holds = holds.add(h.amount().amount());
            }
        }
        return Money.of(ccy, ccy.round(ledger.amount().subtract(holds)));
    }
}
