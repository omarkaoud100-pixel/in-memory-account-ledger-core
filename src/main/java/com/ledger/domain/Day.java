package com.ledger.domain;

/**
 * A day in the replay window, 1..6. A tiny value type so that "booking day" and
 * "value date" can never be confused with an arbitrary int elsewhere in the code.
 */
public record Day(int number) implements Comparable<Day> {

    public Day {
        if (number < 1 || number > 6) {
            throw new IllegalArgumentException("Day out of window (1..6): " + number);
        }
    }

    public static Day of(int n) {
        return new Day(n);
    }

    public boolean onOrBefore(Day other) {
        return this.number <= other.number;
    }

    @Override
    public int compareTo(Day o) {
        return Integer.compare(this.number, o.number);
    }

    @Override
    public String toString() {
        return "Day " + number;
    }
}
