package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.expensetracker.model.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Today's rates from the feeds, against any currency, each from one feed only. */
class LiveRatesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final EcbRates.Feed ECB = new EcbRates.Feed(DAY.minusDays(1), Map.of("EUR", BigDecimal.ONE,
            "USD", new BigDecimal("1.25"), "GBP", new BigDecimal("0.8")));
    private static final EcbRates.Feed OTHER = new EcbRates.Feed(DAY, Map.of("EUR", BigDecimal.ONE,
            "USD", new BigDecimal("1.3"), "ALL", new BigDecimal("100")));

    @Test
    void the_bank_is_used_where_it_publishes_both_and_the_other_feed_only_where_it_does_not() {
        List<LiveRates.Rate> inDollars = LiveRates.against("USD", ECB, OTHER);
        assertEquals(List.of("ALL", "EUR", "GBP"), inDollars.stream().map(LiveRates.Rate::code).toList());
        LiveRates.Rate lek = inDollars.get(0);
        assertEquals(ExchangeRate.Source.EXCHANGE_RATE_API, lek.source(), "the bank does not publish the lek");
        assertEquals(0, new BigDecimal("0.013").compareTo(lek.rate()), "1.3 / 100, both from the same feed");
        assertEquals(DAY, lek.day());
        LiveRates.Rate euro = inDollars.get(1);
        assertEquals(ExchangeRate.Source.ECB, euro.source());
        assertEquals(0, new BigDecimal("1.25").compareTo(euro.rate()));
        assertEquals(0, new BigDecimal("1.5625").compareTo(inDollars.get(2).rate()), "1.25 / 0.8");
    }

    @Test
    void a_currency_only_the_other_feed_has_is_priced_by_it_alone() {
        List<LiveRates.Rate> inLek = LiveRates.against("ALL", ECB, OTHER);
        assertEquals(List.of("EUR", "USD"), inLek.stream().map(LiveRates.Rate::code).toList(),
                "the pound is only in the bank's feed, which has no lek: no mixed rate is made up");
        assertEquals(ExchangeRate.Source.EXCHANGE_RATE_API, inLek.get(1).source());
    }

    @Test
    void with_one_feed_missing_the_other_still_answers() {
        assertEquals(List.of("EUR", "USD"), LiveRates.against("GBP", ECB, null).stream()
                .map(LiveRates.Rate::code).toList());
        assertEquals(List.of(), LiveRates.against("EUR", null, null));
    }
}
