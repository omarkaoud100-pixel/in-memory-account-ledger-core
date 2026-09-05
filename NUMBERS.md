# NUMBERS.md

Every constant this system uses, why it has the value it has, and why not a
different value. All monetary figures are reproduced by hand below so they can be
defended without tooling.

## Currencies and precision

| Currency | Decimal places | Rationale |
|----------|----------------|-----------|
| AED      | 2              | Given by the spec. AED minor unit is the fils (1/100). |
| BHD      | 3              | Given by the spec. BHD minor unit is the fils (1/1000). |

- **Rounding mode: `HALF_UP`.** Chosen deliberately over `HALF_EVEN`
  (banker's rounding). The spec does not name a mode, so this is a judgment call
  (see AMBIGUITIES.md). `HALF_UP` is the intuitive "round .5 away from zero" rule
  a human reproduces by hand under interview pressure, and every rounding decision
  in this exercise except the interest reconciliation is unambiguous anyway (no
  monetary value lands exactly on a half-minor-unit boundary). Using one mode
  everywhere, defined in exactly one place (`CurrencySpec.ROUNDING`), means there
  is a single fact to defend rather than a scattering of ad-hoc calls.
- Amounts are stored at their currency's native scale on construction, so an
  AED value can never silently carry more than 2 places, nor BHD more than 3.

## Overdraft fee: AED 25.00

- Value taken verbatim from the "non-negotiable rules". Not 12.50 (half it),
  not 50 - the spec fixes it at 25.00.
- Assessed **at most once per account per day**, only when that day's
  **forward-time closing balance is negative**, and booked with `value_date`
  equal to the day assessed.
- Modelled per currency so a BHD overdraft would book 25.000. In this scenario
  only ACC-001 (AED) ever goes negative, so only AED 25.00 is ever charged.

## Daily interest rate: 0.04% per day = 0.0004

- Value verbatim from the spec. Applied to the **closing ledger balance,
  positive balances only**.
- It is a **simple daily accrual**, not daily compounding. Justification: the
  rule says accruals "capitalize as a single credit at end of Day 6". If interest
  compounded daily it would already be part of the balance each day and could not
  be a single end-of-window credit. So each day's accrual is computed on that
  day's closing balance **excluding** prior accruals, and the accruals are summed
  and posted once.
- The balance used for interest is the **same forward-time closing balance that
  drives the overdraft fee**. Using two different balance definitions (one for
  fees, one for interest) would be indefensible; see AMBIGUITIES.md.

### ACC-001 interest, by hand

Closing balances (forward time, positive only):

| Day | Closing balance | Accrual = balance x 0.0004 |
|-----|-----------------|----------------------------|
| 1   | 250.00          | 0.100000                   |
| 2   | 250.00          | 0.100000                   |
| 3   | 650.00          | 0.260000                   |
| 4   | 465.00          | 0.186000                   |
| 5   | -155.00 -> negative | 0 (no interest)        |
| 6   | 440.00          | 0.176000                   |

- Raw sum = 0.100 + 0.100 + 0.260 + 0.186 + 0.176 = **0.822**
- Capitalized total = round(0.822, 2dp) = **AED 0.82**

**Reconciliation (this is the subtle number).** Rounding each day independently
gives 0.10 / 0.10 / 0.26 / 0.19 / 0.00 / 0.18 = **0.83**, which is one fils more
than the 0.82 capitalized total. The non-negotiable rule requires the published
daily figures to sum **exactly** to the capitalized total, so we cannot leave
this drift in place and we are forbidden from discarding it (see REJECTED.md).

We use **largest-remainder allocation**:
1. Floor each day to 2dp: 0.10 / 0.10 / 0.26 / 0.18 / 0.00 / 0.17 = 0.81
2. Extra fils to distribute to reach 0.82 = 1
3. Rank by fractional remainder: Day 4 (0.186 -> .006) and Day 6 (0.176 -> .006)
   are the two largest and they **tie**.
4. Tie-break = **earliest day wins** (documented policy) -> the fils goes to Day 4.

Published daily accruals: **0.10 / 0.10 / 0.26 / 0.19 / 0.00 / 0.17 = 0.82** exactly.

> The tie-break is a genuine judgment call the spec does not settle. The one
> intentionally-failing test (`FailingByDesignTest`) encodes the opposite
> "latest day wins" policy to keep that choice visible. See AMBIGUITIES.md.

### ACC-002 interest, by hand

ACC-002 only receives E10 (BHD 10.000, value_date Day 5).

| Day | Closing balance | Accrual = balance x 0.0004 |
|-----|-----------------|----------------------------|
| 1-4 | 0.000           | 0                          |
| 5   | 10.000          | 0.004000                   |
| 6   | 10.000          | 0.004000                   |

- Raw sum = 0.008, capitalized total = round(0.008, 3dp) = **BHD 0.008**.
- Independently-rounded daily figures already sum to 0.008, so no reallocation
  is needed.

## E10 instalment split: 3.334 + 3.333 + 3.333

- The spec says "three equal instalments" of BHD 10.000. In 3dp arithmetic there
  is no value `x` with `3x = 10.000`, so "equal" is impossible and "equal as
  possible while conserving the total" is the only coherent reading.
- 10.000 / 3 = 3.3333... -> floor to 3dp = 3.333, remainder 0.001 -> one
  instalment absorbs it: **3.334 + 3.333 + 3.333 = 10.000**.
- Not 3.334 x 3 = 10.002 (creates money) and not 3.333 x 3 = 9.999 (destroys
  money). Conservation of the posted total is non-negotiable. See REJECTED.md,
  criterion 7.

## Opening balances: 0.00 (AED) / 0.000 (BHD)

- Given by the spec. Modelled explicitly on the `Account` rather than assumed, so
  a non-zero opening balance would be a one-line change.

## Window: Day 1 .. Day 6

- Given by the spec. Enforced by the `Day` value type, which rejects any day
  outside 1..6.

## Final ledger balances (after interest capitalization)

- **ACC-001: AED 440.82** = 440.00 closing + 0.82 interest.
- **ACC-002: BHD 10.008** = 10.000 closing + 0.008 interest.
