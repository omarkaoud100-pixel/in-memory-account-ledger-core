package com.ledger.domain;

/**
 * Static account definition: id, currency and opening balance. The opening
 * balance for both accounts in this exercise is zero, but it is modelled
 * explicitly rather than assumed.
 *
 * @author Omar Kaoud
 */
public record Account(String id, CurrencySpec currency, Money openingBalance) {

    public static Account of(String id, CurrencySpec currency) {
        return new Account(id, currency, Money.zero(currency));
    }
}
