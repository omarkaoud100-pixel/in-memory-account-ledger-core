package com.ledger.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An immutable monetary amount bound to a currency. The amount is always held
 * at the currency's native scale, so equality and arithmetic are exact.
 *
 * <p>Design note (defend this): using a dedicated value object rather than raw
 * BigDecimal makes it impossible to accidentally add AED to BHD, and centralises
 * per-currency rounding in one place.
 *
 * @author Omar Kaoud
 */
public record Money(CurrencySpec currency, BigDecimal amount) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        // Normalise to the currency scale on construction so every Money is canonical.
        amount = currency.round(amount);
    }

    public static Money of(CurrencySpec currency, String amount) {
        return new Money(currency, new BigDecimal(amount));
    }

    public static Money of(CurrencySpec currency, BigDecimal amount) {
        return new Money(currency, amount);
    }

    public static Money zero(CurrencySpec currency) {
        return new Money(currency, currency.zero());
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, amount.add(other.amount));
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, amount.subtract(other.amount));
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    private void requireSameCurrency(Money other) {
        if (this.currency != other.currency) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return currency + " " + amount.toPlainString();
    }
}
