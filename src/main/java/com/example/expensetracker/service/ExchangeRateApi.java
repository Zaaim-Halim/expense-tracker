package com.example.expensetracker.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ExchangeRate-API's free daily rates: about 160 currencies, with no account
 * or key, for the ones the European Central Bank does not publish (the
 * Albanian lek, the Moroccan dirham…). Its terms ask for a credit wherever its
 * rates are shown: {@link #CREDIT}, linking to {@link #SITE}.
 */
public final class ExchangeRateApi {

    /** Today's rates, as what one euro buys. */
    public static final URI FEED = URI.create("https://open.er-api.com/v6/latest/EUR");

    /** The credit its terms ask for, and where it links. */
    public static final String CREDIT = "Rates by ExchangeRate-API";
    public static final URI SITE = URI.create("https://www.exchangerate-api.com");

    private static final Pattern SUCCESS = Pattern.compile("\"result\"\\s*:\\s*\"success\"");
    private static final Pattern BASE = Pattern.compile("\"base_code\"\\s*:\\s*\"EUR\"");
    private static final Pattern UPDATED = Pattern.compile("\"time_last_update_unix\"\\s*:\\s*(\\d+)");
    private static final Pattern RATES = Pattern.compile("\"rates\"\\s*:\\s*\\{([^{}]*)\\}");
    private static final Pattern ENTRY =
            Pattern.compile("\\s*\"([A-Z]{3})\"\\s*:\\s*(\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s*");

    private ExchangeRateApi() {
    }

    /** Downloads today's rates. */
    public static EcbRates.Feed fetch() throws IOException {
        return parse(EcbRates.download(FEED));
    }

    /**
     * Reads the answer. Anything unexpected rejects all of it, as the
     * European Central Bank's feed is read.
     *
     * @throws IllegalArgumentException when it is not the answer expected
     */
    public static EcbRates.Feed parse(String json) {
        if (json == null || !SUCCESS.matcher(json).find()) {
            throw new IllegalArgumentException("ExchangeRate-API did not answer with rates");
        }
        if (!BASE.matcher(json).find()) {
            throw new IllegalArgumentException("ExchangeRate-API's rates are not per euro");
        }
        Matcher updated = UPDATED.matcher(json);
        if (!updated.find()) {
            throw new IllegalArgumentException("ExchangeRate-API's rates name no day");
        }
        LocalDate day = Instant.ofEpochSecond(Long.parseLong(updated.group(1))).atZone(ZoneOffset.UTC).toLocalDate();
        Matcher rates = RATES.matcher(json);
        if (!rates.find()) {
            throw new IllegalArgumentException("ExchangeRate-API's answer holds no rates");
        }
        Map<String, BigDecimal> perEuro = new TreeMap<>();
        for (String entry : rates.group(1).split(",")) {
            Matcher pair = ENTRY.matcher(entry);
            if (!pair.matches()) {
                throw new IllegalArgumentException("ExchangeRate-API has a rate that cannot be read: " + entry.strip());
            }
            BigDecimal value = new BigDecimal(pair.group(2));
            if (value.signum() <= 0 || perEuro.put(pair.group(1), value) != null) {
                throw new IllegalArgumentException("ExchangeRate-API's rate for " + pair.group(1) + " cannot be used");
            }
        }
        if (!BigDecimal.ONE.equals(perEuro.get("EUR").stripTrailingZeros())) {
            throw new IllegalArgumentException("ExchangeRate-API's euro is not worth one euro");
        }
        perEuro.put("EUR", BigDecimal.ONE);
        return new EcbRates.Feed(day, Map.copyOf(perEuro));
    }
}
