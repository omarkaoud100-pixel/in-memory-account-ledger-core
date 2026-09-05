# AMBIGUITIES.md

Every ambiguity found in the specification, the options considered, and the
resolution chosen. An empty version of this file is a fail; the point is to show
where the spec is silent and where a defensible judgment was required.

---

## A1. "Closing ledger balance" vs a back-dated event (the core ambiguity)

**Tension.** E7 is booked on Day 5 but carries `value_date` Day 2. The overdraft
fee rule keys off "that day's closing ledger balance (all entries with
value_date <= that day)". Read literally, E7 makes the *Day 2* balance negative,
which would suggest a retroactive Day-2 fee.

**Resolution.** We separate two distinct concepts:
- **Booking day**: when the event physically enters the append-only log (forward
  time). An event cannot influence any day before it exists.
- **Value date**: the date used to slice the ledger for reporting.

The **forward-time closing balance** for day D sums entries with
`value_date <= D` **and** `booking_day <= D`. Fees and interest use this. A
separate **as-of** query answers historical questions like "the Day 2 balance as
evaluated at end of Day 5".

Consequences:
- The fee that E7 triggers is assessed on **Day 5** (when the negative close
  actually happens in forward time), not retroactively on Day 2.
- "The Day 2 balance evaluated at end of Day 5" = -370.00 is answered by the
  as-of query, and is a *reporting* fact, not a fee trigger.

This is the single most important interpretation in the whole exercise and it
drives criteria 1, 2 and 6.

## A2. What does a SETTLEMENT post to the ledger?

**Options.** (a) Settlement posts the settled amount as a real debit and releases
the hold; (b) settlement only converts the hold with no new posting.

**Resolution.** (a). A hold never touches the ledger balance; only a settlement's
actual debit does. Auth-A held 200 and settles for 185, so we post -185.00 and
release the 200 hold. The 15 difference simply disappears (the hold is gone, only
the real amount posted). Without this, ledger balances never reflect card spend.

## A3. Over-settlement / partial settlement residue

Auth-A settles for **less** than it held (185 < 200). We treat the hold as fully
released on settlement and do not retain a residual 15 hold. The spec gives no
partial-settlement protocol, and a single settlement closing an auth is the
common card-network default. A settlement for *more* than the hold is not present
in the stream; our model would still post the actual settled amount.

## A4. Overdraft fee: "once per day" scope and timing

- "Once per day per account" - enforced by assessing at most one fee per account
  at end of each day.
- Booked with `value_date` = the day assessed - so the fee itself is dated to the
  day it is charged, and it participates in later balances from that day forward.
- The fee is evaluated **after** that day's events are applied but the interest
  basis is taken **after** the fee, so a fee that pushes the day negative does not
  earn (negative) interest.

## A5. Does a REVERSAL undo a fee?

**Resolution.** No. A reversal is a new compensating entry (append-only). It nets
the *balance* effect of the reversed posting, but a fee already assessed on an
earlier day stays on the book. This is what makes criterion 6 false. Reversing a
cause does not rewrite history in an append-only ledger.

## A6. Interest balance basis: forward-time vs value-dated

Interest could be computed on (a) the forward-time closing balance, or (b) a
value-date-recomputed balance (which would retroactively make Day 2 negative).
We use (a), the **same** balance definition as the fee. Using different bases for
two rules that both say "closing ledger balance" would be internally
inconsistent and impossible to defend.

## A7. Simple accrual vs daily compounding

The rule "capitalize as a single credit at end of Day 6" implies interest is
**not** added to the balance until Day 6, hence simple (non-compounding) daily
accrual. If it compounded, there could be no single end-of-window credit.

## A8. Rounding mode

The spec fixes decimal places but not the rounding mode. We chose `HALF_UP`
system-wide (see NUMBERS.md). No monetary value in this scenario sits on an exact
half-minor-unit boundary, so `HALF_UP` vs `HALF_EVEN` changes nothing here except
the interest reconciliation, which is handled by explicit largest-remainder
allocation rather than by naive per-day rounding.

## A9. Interest reconciliation tie-break (documented, and deliberately failing)

When two days tie on the fractional remainder that decides who absorbs the
rounding fils (Day 4 and Day 6 both at .006), the spec does not say who wins. We
chose **earliest day wins**. This is arbitrary but must be *some* fixed rule to
be deterministic. The intentionally-failing test `FailingByDesignTest` encodes
the competing "latest day wins" policy so the ambiguity is visible in the test
suite rather than hidden. Both policies still sum to exactly 0.82.

## A10. "Three equal instalments" of an indivisible amount

10.000 / 3 is not representable in 3dp. "Equal" is literally impossible, so we
read it as "equal as possible, conserving the total": 3.334 + 3.333 + 3.333.
See NUMBERS.md and REJECTED.md (criterion 7).

## A11. Auth-B approval depends on the fee ordering

Auth-B (hold 90) arrives on Day 5. Whether it is approved depends on the
available balance at that moment. After E7 posts, ACC-001 is already negative, so
available (ledger - holds) is well below zero and Auth-B is **declined**
regardless of the 90 hold. We evaluate the authorization against the live
available balance at the time the event is processed.

## A12. E10 value_date and booking day both Day 5

E10 and its instalments are booked Day 5 with value_date Day 5, so ACC-002 closes
at 10.000 from Day 5 onward. No ambiguity in the dating; the only subtlety is the
instalment split (A10).
