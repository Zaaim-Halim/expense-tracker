package com.example.expensetracker.service;

import com.example.expensetracker.model.ExchangeRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Today's rates as the feeds publish them, for looking at: every currency
 * either feed has, worth so much of one chosen currency. Nothing here is
 * kept; the rates a transaction uses are only ever the ones saved.
 */
public final class LiveRates {

    private LiveRates() {
    }

    /**
     * One currency's rate today.
     *
     * @param code   the currency one unit of which is priced
     * @param rate   what one unit is worth in the chosen currency
     * @param source the feed it came from
     * @param day    the day the feed's rates are for
     */
    public record Rate(String code, BigDecimal rate, ExchangeRate.Source source, java.time.LocalDate day) {
    }

    /**
     * Every currency either feed publishes, other than {@code against},
     * worth so much of it.
     *
     * <p>Each rate comes from one feed only, never a mix: the European Central
     * Bank's when it publishes both currencies, ExchangeRate-API's otherwise.
     * One unit of X is worth {@code perEuro(against) / perEuro(X)}.
     *
     * @param ecb   the bank's feed, or null when it could not be had
     * @param other ExchangeRate-API's, or null when it could not be had
     */
    public static List<Rate> against(String against, EcbRates.Feed ecb, EcbRates.Feed other) {
        TreeSet<String> codes = new TreeSet<>();
        if (ecb != null) {
            codes.addAll(ecb.perEuro().keySet());
        }
        if (other != null) {
            codes.addAll(other.perEuro().keySet());
        }
        codes.remove(against);
        List<Rate> found = new ArrayList<>();
        for (String code : codes) {
            Rate rate = from(ecb, ExchangeRate.Source.ECB, against, code);
            if (rate == null) {
                rate = from(other, ExchangeRate.Source.EXCHANGE_RATE_API, against, code);
            }
            if (rate != null) {
                found.add(rate);
            }
        }
        return found;
    }

    private static Rate from(EcbRates.Feed feed, ExchangeRate.Source source, String against, String code) {
        if (feed == null) {
            return null;
        }
        BigDecimal target = feed.perEuro().get(against);
        BigDecimal unit = feed.perEuro().get(code);
        if (target == null || unit == null) {
            return null;
        }
        BigDecimal rate = target.divide(unit, Money.RATE_SCALE, RoundingMode.HALF_EVEN).stripTrailingZeros();
        return rate.signum() > 0 ? new Rate(code, rate, source, feed.day()) : null;
    }
}
