# REJECTED.md

Two things live here: (1) acceptance criteria that are wrong, refused with
reasons; (2) approaches started and abandoned mid-build.

---

## Part 1 - Refused acceptance criteria

The brief states some criteria are wrong and must be identified and refused. Each
is judged against the non-negotiable rules and the day-by-day numbers in
NUMBERS.md.

### Criterion 1 - ACCEPTED (correct)
> "The Day 2 closing ledger balance, evaluated at end of Day 5 and before any fee,
> is AED -370.00."

Correct, and it tests whether you separate value date from booking day. Evaluated
*as of end of Day 5*, the entries with `value_date <= Day 2` that have been booked
are E1 (+1200.00), E2 (-950.00) and E7 (-620.00) - E7 is booked on Day 5 with a
back-dated value_date of Day 2. E9 (the reversal) is booked on Day 6 and is not
yet visible. 1200 - 950 - 620 = **-370.00**. Verified by
`Ledger.valueDatedSliceAsOf(ACC-001, Day 2, Day 5)`.

Note this is a *reporting* figure, not a fee trigger (see criterion 2).

### Criterion 2 - REFUSED
> "E7 causes exactly one overdraft fee to be assessed, on Day 2."

The **count** is right (exactly one fee) but the **day is wrong**. A fee is
assessed on the day the negative closing balance actually occurs in forward time.
E7 is booked on Day 5; Day 2 already closed at +250.00 back when Day 2 was the
current day, and an append-only ledger does not travel back in time to charge a
fee on a day that closed positive. The fee is assessed on **Day 5**, dated
value_date Day 5. Accepting "on Day 2" would require retroactively mutating a
settled day, violating the append-only rule. Refused.

### Criterion 3 - ACCEPTED (correct)
> "The Day 4 settlement of Auth-A must be accepted."

Auth-A was authorized by E3 and is an active hold, so E5 settles it: post
-185.00, release the 200 hold. Correct.

### Criterion 4 - ACCEPTED (correct)
> "Any settlement referencing an authorization ID not present in the ledger must
> be rejected and the funds must not leave the account."

This is exactly E6 (Auth-Z, which had no authorization event). We reject it and
post nothing. Correct and enforced by test `unknownAuthSettlementRejected`.

### Criterion 5 - REFUSED (premise is false)
> "If Auth-B is approved, its hold reduces available balance but not ledger
> balance."

The conditional's *body* is a true statement about how holds work. But the
*premise* is false in this scenario: on Day 5, after E7 posts, ACC-001's available
balance is already negative, so applying a 90 hold cannot keep available at or
above zero. Auth-B is therefore **declined** and never becomes a hold. Presenting
this as a fact about the run ("its hold reduces available balance") is wrong
because there is no hold. Refused as stated; the underlying rule is nonetheless
implemented correctly for any auth that *is* approved (e.g. Auth-A).

### Criterion 6 - REFUSED
> "After E9, all balances and fees return to their pre-E7 values."

Balances net back (the reversal cancels E7's balance effect), but **fees do not**.
The overdraft fee assessed on Day 5 is an append-only entry; reversing E7 on Day 6
does not un-assess it. So the fee state after E9 is not the pre-E7 state. Refused.
Enforced by `reversalDoesNotUndoFee` (one fee remains; Day 6 closes at 440.00,
which is 465.00 minus the retained 25.00 fee).

### Criterion 7 - REFUSED
> "The three BHD instalments in E10 must each be BHD 3.334."

3.334 x 3 = **10.002**, which is 0.002 more than the 10.000 being credited. That
invents money and breaks conservation of the posted total. BHD is 3dp so no
single repeated value divides 10.000 into three. The correct equal-as-possible,
total-conserving split is **3.334 + 3.333 + 3.333 = 10.000**. Refused.

### Criterion 8 - REFUSED
> "If the rounded daily interest accruals do not sum to the capitalized total, the
> remainder is discarded."

Directly contradicts a non-negotiable rule: "The rounded daily accruals must sum
exactly to the capitalized total." Discarding the remainder guarantees they do
*not* reconcile. We instead use largest-remainder allocation so the published
daily figures sum exactly to the capitalized total (0.82 for ACC-001). Refused.

### Summary

| Criterion | Verdict  | Reason |
|-----------|----------|--------|
| 1 | Accept | -370.00 is the correct as-of-Day-5 value-dated slice |
| 2 | Refuse | fee is on Day 5, not Day 2 (append-only, forward time) |
| 3 | Accept | Auth-A is a live hold; settlement valid |
| 4 | Accept | unknown-auth settlement correctly rejected |
| 5 | Refuse | premise false: Auth-B is declined, so no hold exists |
| 6 | Refuse | append-only fee is not reversed by E9 |
| 7 | Refuse | 3.334x3 = 10.002 breaks conservation; use 3.334/3.333/3.333 |
| 8 | Refuse | contradicts the exact-sum non-negotiable rule |

---

## Part 2 - Approaches abandoned mid-build

### R1. Value-dated-only closing balance (no booking-day cutoff)
The first `valueDatedSlice` summed every entry with `value_date <= day` across the
*whole* finished log. It gave +250.00 for the Day-2 question because the Day-6
reversal (value_date Day 2) had already cancelled E7. That answered the wrong
question. Abandoned in favour of an explicit **as-of** query
(`valueDatedSliceAsOf`) that also bounds by booking day, which is what "evaluated
at end of Day 5" actually means. This was caught by the failing C1 test, not by
inspection - the test paid for itself immediately.

### R2. Mutating a running balance per account
An early sketch kept a mutable `balance` field per account and adjusted it as
events applied. This cannot answer value-dated or as-of questions (it only knows
"now"), and it quietly conflicts with the append-only requirement. Replaced with
a pure append-only entry log plus balance *queries* that fold over the log. Slower
in theory, but the window is six days and correctness/defensibility win.

### R3. Retroactive fee assessment
Briefly considered honouring criterion 2 literally by re-opening Day 2 when E7
arrives and charging a Day-2 fee. Rejected: it violates append-only and produces
a self-contradiction with the "day assessed" wording. Kept fees strictly in
forward time.

### R4. Spring Boot scaffold
Considered building on Spring Boot (familiarity, `CommandLineRunner`). Rejected:
the brief forbids web/persistence/UI/database, which is Spring Boot's entire
reason to exist. A framework context around a pure domain library is weight with
no benefit and an obvious thing to be challenged on. Went with plain Java 21 +
Maven + JUnit 5.
