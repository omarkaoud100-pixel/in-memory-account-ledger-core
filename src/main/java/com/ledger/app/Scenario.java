package com.ledger.app;

import com.ledger.domain.Account;
import com.ledger.domain.CurrencySpec;
import com.ledger.domain.Day;
import com.ledger.domain.Event;
import com.ledger.domain.Money;

import java.util.ArrayList;
import java.util.List;

/**
 * The fixed six-day scenario: the two accounts and the ten-event stream, exactly
 * as specified. This is the single source of truth for the replay and the tests.
 */
public final class Scenario {

    private Scenario() {}

    public static final Account ACC_001 = Account.of("ACC-001", CurrencySpec.AED);
    public static final Account ACC_002 = Account.of("ACC-002", CurrencySpec.BHD);

    public static List<Event> eventStream() {
        List<Event> s = new ArrayList<>();

        // E1 - Day 1 - CREDIT ACC-001 AED 1,200.00 - value_date Day 1
        s.add(new Event.Credit("E1", Day.of(1), "ACC-001", Money.of(CurrencySpec.AED, "1200.00"), Day.of(1)));

        // E2 - Day 1 - DEBIT ACC-001 AED 950.00 - value_date Day 1
        s.add(new Event.Debit("E2", Day.of(1), "ACC-001", Money.of(CurrencySpec.AED, "950.00"), Day.of(1)));

        // E3 - Day 2 - AUTHORIZATION ACC-001 Auth-A hold AED 200.00 - value_date Day 2
        s.add(new Event.Authorization("E3", Day.of(2), "ACC-001", "Auth-A",
                Money.of(CurrencySpec.AED, "200.00"), Day.of(2)));

        // E4 - Day 3 - CREDIT ACC-001 AED 400.00 - value_date Day 3
        s.add(new Event.Credit("E4", Day.of(3), "ACC-001", Money.of(CurrencySpec.AED, "400.00"), Day.of(3)));

        // E5 - Day 4 - SETTLEMENT ACC-001 Auth-A settles for AED 185.00 - value_date Day 4
        s.add(new Event.Settlement("E5", Day.of(4), "ACC-001", "Auth-A",
                Money.of(CurrencySpec.AED, "185.00"), Day.of(4)));

        // E6 - Day 4 - SETTLEMENT ACC-001 Auth-Z settles for AED 180.00 - value_date Day 4
        //      (Auth-Z has no preceding authorization event)
        s.add(new Event.Settlement("E6", Day.of(4), "ACC-001", "Auth-Z",
                Money.of(CurrencySpec.AED, "180.00"), Day.of(4)));

        // E7 - Day 5 - DEBIT ACC-001 AED 620.00 - value_date Day 2
        s.add(new Event.Debit("E7", Day.of(5), "ACC-001", Money.of(CurrencySpec.AED, "620.00"), Day.of(2)));

        // E8 - Day 5 - AUTHORIZATION ACC-001 Auth-B hold AED 90.00 - value_date Day 5
        s.add(new Event.Authorization("E8", Day.of(5), "ACC-001", "Auth-B",
                Money.of(CurrencySpec.AED, "90.00"), Day.of(5)));

        // E9 - Day 6 - REVERSAL ACC-001 reverses E7 - value_date Day 2
        s.add(new Event.Reversal("E9", Day.of(6), "ACC-001", "E7", Day.of(2)));

        // E10 - Day 5 - CREDIT ACC-002 BHD 10.000, posted as three equal instalments - value_date Day 5
        //       Equal-as-possible split summing EXACTLY to 10.000 in 3dp: 3.334 + 3.333 + 3.333.
        //       (3.334 * 3 = 10.002 would violate conservation; see REJECTED.md.)
        List<String> instalments = List.of("3.334", "3.333", "3.333");
        for (int i = 0; i < instalments.size(); i++) {
            s.add(new Event.Credit("E10." + (i + 1), Day.of(5), "ACC-002",
                    Money.of(CurrencySpec.BHD, instalments.get(i)), Day.of(5)));
        }

        return s;
    }
}
