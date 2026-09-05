# In-Memory Account Ledger Core

An event-sourced, append-only account ledger replayed over a fixed six-day
window (Day 1 .. Day 6). No web layer, no persistence, no UI, no database - just a
domain core and a runnable harness / test suite that replays the event stream and
reports, per day, the closing ledger balance, overdraft fee assessments,
authorization states, and errors, followed by end-of-window interest
capitalization.

## Requirements

- JDK 21 (bytecode target is Java 21; it also runs on newer JDKs).
- Maven 3.9+.

## Run the replay harness

```bash
mvn -q compile exec:java
```

This prints a per-day report for both accounts and the interest capitalization
detail. Sample of what to look for:

```
---- Day 5 ----
  ACC-001
    closing ledger balance : AED -180.00
    available balance      : AED -180.00
    overdraft fee today    : YES (25)
    authorizations         : [Auth-A=SETTLED AED 185.00] [Auth-B=DECLINED AED 90.00]
...
---- INTEREST CAPITALIZATION (end of Day 6) ----
  ACC-001 daily accruals: 0.10 0.10 0.26 0.19 0.00 0.17
    capitalized total: AED 0.82
```

## Run the tests

```bash
mvn -q test
```

The suite has **14 tests: 13 pass and 1 fails by design**. The single red test
(`FailingByDesignTest`) is intentional and annotated - it exposes an
underspecified interest tie-break rather than a defect. See its Javadoc and
AMBIGUITIES.md (A9).

> If your environment routes Maven through a restricted mirror and dependency
> resolution stalls, the domain code itself has zero third-party dependencies and
> compiles with `javac` directly; only the tests need JUnit.

## How to read the output

- **closing ledger balance** - forward-time closing for that day: the sum of all
  posted entries whose `value_date <= day` and that were booked on or before that
  day. This is what drives fees and interest.
- **available balance** - ledger balance minus active holds. Authorizations are
  approved only if available stays at or above zero after the hold.
- **overdraft fee today** - whether an AED 25.00 fee was assessed that day (once
  per account per day, when the day closes negative).
- **authorizations** - per-auth lifecycle: `APPROVED`, `DECLINED`, `SETTLED`, or
  `REJECTED_NO_AUTH`.
- **ERROR lines** - rejected events (e.g. a settlement against an unknown
  authorization, or a declined authorization).

## Design in one paragraph

Events (`Event`, a sealed interface) are immutable requests. Replaying them
appends immutable `LedgerEntry` records to an append-only log; nothing is ever
mutated or deleted. Balances are *queries* that fold over the log, so the same
log can answer "today's closing balance", "the balance of a past value date", and
"that value date as it looked on an earlier day" (`valueDatedSliceAsOf`). Holds
are tracked separately from the ledger and only affect the available balance.
Money is a currency-bound value object that rounds to the currency's own scale
(AED 2dp, BHD 3dp) with a single system-wide rounding mode. Interest is a simple
daily accrual on positive closing balances, reconciled with largest-remainder
allocation so the rounded daily figures sum exactly to the single capitalized
credit posted at end of Day 6.

## Companion documents

- **NUMBERS.md** - every constant and every hand-reproducible figure.
- **AMBIGUITIES.md** - every ambiguity found and how it was resolved.
- **REJECTED.md** - which acceptance criteria are wrong and why, plus approaches
  abandoned mid-build.
- **WORKLOG.md** - timestamped build log.

## Project layout

```
src/main/java/com/ledger/
  domain/   Money, CurrencySpec, Day, Event, LedgerEntry, Account,
            Ledger, RemainderAllocator, ReplayResult, ReplayEngine
  app/      Scenario (the fixed 10-event stream), ReplayMain (harness)
src/test/java/com/ledger/
  AcceptanceCriteriaTest, RemainderAllocatorTest, FailingByDesignTest
```
