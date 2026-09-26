package com.example.expensetracker.model;

import java.math.BigDecimal;

/**
 * What is known of a credit card or a loan beyond its balance. Every part is
 * optional: each one known lets more be worked out.
 *
 * @param accountId    the card's or the loan's account
 * @param limitCents   a card's credit limit, or what a loan borrowed at the
 *                     start, in the account's minor units; null if not known
 * @param apr          the yearly interest rate, as a percentage (19.9 for
 *                     19.9%); null if not known
 * @param minimumCents the payment made each month, in the account's minor
 *                     units; null if not known
 * @param dueDay       the day of the month a payment is due, 1 to 31; null
 *                     if not known
 */
public record Debt(long accountId, Long limitCents, BigDecimal apr, Long minimumCents, Integer dueDay) {

    /** Whether nothing at all is known. */
    public boolean empty() {
        return limitCents == null && apr == null && minimumCents == null && dueDay == null;
    }
}
