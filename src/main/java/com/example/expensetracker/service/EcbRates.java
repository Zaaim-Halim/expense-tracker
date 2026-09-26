package com.example.expensetracker.service;

import com.example.expensetracker.model.ExchangeRate;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The European Central Bank's daily reference rates: free, published every
 * working day around 16:00 CET, and needing no account or key.
 *
 * <p>The feed says what one euro buys of about thirty currencies. Rates here
 * say what one unit of a currency is worth in the base, so each is worked out
 * from two of the feed's: exactly, as decimals, never as doubles.
 */
public final class EcbRates {

    /** Where the feed is published. */
    public static final URI FEED = URI.create("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml");

    /** How long a connection, or the whole answer, may take. */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final Pattern DAY = Pattern.compile("time=['\"](\\d{4}-\\d{2}-\\d{2})['\"]");
    private static final Pattern CUBE = Pattern.compile("<Cube\\s+currency=");
    private static final Pattern RATE = Pattern.compile(
            "<Cube\\s+currency=['\"]([A-Z]{3})['\"]\\s+rate=['\"](\\d+(?:\\.\\d+)?)['\"]\\s*/>");

    private EcbRates() {
    }

    /**
     * One day's feed: what a euro was worth in each currency. The euro itself
     * is in it, at one, so every currency is looked up the same way.
     *
     * @param day     the day the rates are for
     * @param perEuro units of each currency for one euro
     */
    public record Feed(LocalDate day, Map<String, BigDecimal> perEuro) {
    }

    /**
     * What was fetched and kept.
     *
     * @param day     the day the rates are for
     * @param added   rates added; days that already had one keep it
     * @param missing currencies in use that no feed published
     * @param problem why the second feed could not be used, when it was
     *                needed and could not; null otherwise
     */
    public record Result(LocalDate day, int added, List<String> missing, String problem) {
    }

    /** A feed to download only if it is needed. */
    @FunctionalInterface
    public interface Loader {
        Feed load() throws IOException;
    }

    /** Downloads today's feed. */
    public static String download(URI feed) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(feed).timeout(TIMEOUT).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException(feed.getHost() + " answered " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("the download was interrupted", e);
        }
    }

    /**
     * Reads the feed. Anything unexpected in it rejects all of it: a feed
     * half understood would give some currencies wrong rates.
     *
     * @throws IllegalArgumentException when it is not the feed expected
     */
    public static Feed parse(String xml) {
        if (xml == null) {
            throw new IllegalArgumentException("the feed is empty");
        }
        Matcher day = DAY.matcher(xml);
        if (!day.find()) {
            throw new IllegalArgumentException("the feed names no day");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(day.group(1));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("the feed's day is not a date: " + day.group(1), e);
        }
        if (day.find()) {
            throw new IllegalArgumentException("the feed names more than one day");
        }

        Map<String, BigDecimal> perEuro = new TreeMap<>();
        Matcher rate = RATE.matcher(xml);
        while (rate.find()) {
            BigDecimal value = new BigDecimal(rate.group(2));
            if (value.signum() <= 0 || perEuro.put(rate.group(1), value) != null) {
                throw new IllegalArgumentException("the feed's rate for " + rate.group(1) + " cannot be used");
            }
        }
        long entries = CUBE.matcher(xml).results().count();
        if (perEuro.isEmpty() || entries != perEuro.size()) {
            throw new IllegalArgumentException("the feed has rates that cannot be read");
        }
        perEuro.put("EUR", BigDecimal.ONE);
        return new Feed(date, Map.copyOf(perEuro));
    }

    /**
     * The rates of {@code wanted} currencies in {@code base}, from a feed.
     *
     * <p>One unit of a currency is worth {@code perEuro(base) / perEuro(it)}
     * of the base: the euro rates of both, divided. That is also right for
     * the euro itself (one euro is {@code perEuro(base)}) and for a euro base
     * (one unit is {@code 1 / perEuro(it)}), since the euro's own rate is one.
     *
     * @throws IllegalArgumentException when the feed does not publish the base
     */
    public static List<ExchangeRate> ratesIn(Feed feed, String base, Collection<String> wanted) {
        return ratesIn(feed, base, wanted, ExchangeRate.Source.ECB);
    }

    /** {@link #ratesIn(Feed, String, Collection)}, from a feed published by {@code source}. */
    public static List<ExchangeRate> ratesIn(Feed feed, String base, Collection<String> wanted,
            ExchangeRate.Source source) {
        BigDecimal basePerEuro = feed.perEuro().get(base);
        if (basePerEuro == null) {
            throw new IllegalArgumentException(source.label() + " does not publish rates for " + base
                    + ", the base currency, so none can be worked out");
        }
        List<ExchangeRate> rates = new ArrayList<>();
        for (String code : new java.util.TreeSet<>(wanted)) {
            BigDecimal perEuro = feed.perEuro().get(code);
            if (code.equals(base) || perEuro == null) {
                continue;
            }
            BigDecimal rate = basePerEuro.divide(perEuro, Money.RATE_SCALE, RoundingMode.HALF_EVEN)
                    .stripTrailingZeros();
            if (rate.signum() > 0) {
                rates.add(new ExchangeRate(code, feed.day(), rate, source));
            }
        }
        return rates;
    }

    /** The wanted currencies the feed does not publish, other than the base. */
    public static List<String> missing(Feed feed, String base, Collection<String> wanted) {
        return new java.util.TreeSet<>(wanted).stream()
                .filter(code -> !code.equals(base) && !feed.perEuro().containsKey(code))
                .toList();
    }
}
