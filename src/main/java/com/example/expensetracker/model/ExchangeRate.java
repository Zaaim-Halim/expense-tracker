package com.example.expensetracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What one unit of a currency is worth in the base currency, from a day on,
 * until a later rate for the same currency replaces it.
 *
 * @param currency    the currency's code
 * @param effectiveOn the first day it applies to
 * @param rate        units of the base currency for one unit of {@code currency}
 */
public record ExchangeRate(String currency, LocalDate effectiveOn, BigDecimal rate) {
}
