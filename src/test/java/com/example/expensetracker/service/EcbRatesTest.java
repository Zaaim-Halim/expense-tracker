package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.ExchangeRate;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The European Central Bank's feed: read strictly, and turned into rates exactly. */
class EcbRatesTest {

    /** A copy of the real feed, as the bank publishes it. */
    private static String realFeed() throws IOException {
        try (InputStream in = EcbRatesTest.class.getResourceAsStream("/ecb-daily.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static EcbRates.Feed feed(String usd, String gbp) {
        return new EcbRates.Feed(LocalDate.of(2026, 9, 25), Map.of("EUR", BigDecimal.ONE,
                "USD", new BigDecimal(usd), "GBP", new BigDecimal(gbp), "JPY", new BigDecimal("179.70")));
    }

    @Test
    void the_real_feed_is_read_with_its_day_and_every_rate() throws IOException {
        EcbRates.Feed feed = EcbRates.parse(realFeed());
        assertTrue(feed.perEuro().size() > 20, "only " + feed.perEuro().size() + " rates");
        assertEquals(BigDecimal.ONE, feed.perEuro().get("EUR"), "the euro is at one");
        assertTrue(feed.perEuro().containsKey("USD") && feed.perEuro().containsKey("JPY"));
        assertTrue(feed.day().isAfter(LocalDate.of(2026, 1, 1)));
    }

    @Test
    void with_a_euro_base_a_rate_is_one_over_the_feeds() {
        List<ExchangeRate> rates = EcbRates.ratesIn(feed("1.1403", "0.8435"), "EUR", List.of("USD", "JPY"));
        assertEquals(List.of("JPY", "USD"), rates.stream().map(ExchangeRate::currency).toList());
        assertEquals(new BigDecimal("0.0055648303"), rates.get(0).rate(), "1 / 179.70");
        assertEquals(new BigDecimal("0.8769622029"), rates.get(1).rate(), "1 / 1.1403");
        assertTrue(rates.stream().allMatch(rate -> rate.source() == ExchangeRate.Source.ECB
                && rate.effectiveOn().equals(LocalDate.of(2026, 9, 25))));
    }

    @Test
    void with_another_base_a_rate_is_worked_out_from_both_and_the_euro_is_the_bases_own() {
        List<ExchangeRate> rates = EcbRates.ratesIn(feed("1.1403", "0.8435"), "USD", List.of("EUR", "GBP", "USD"));
        Map<String, BigDecimal> by = Map.of(rates.get(0).currency(), rates.get(0).rate(),
                rates.get(1).currency(), rates.get(1).rate());
        assertEquals(2, rates.size(), "the base itself gets no rate");
        assertEquals(new BigDecimal("1.1403"), by.get("EUR"), "one euro is the base's own euro rate");
        assertEquals(new BigDecimal("1.3518672199"), by.get("GBP"), "1.1403 / 0.8435, to ten places");
    }

    @Test
    void a_base_the_feed_does_not_publish_is_said_rather_than_guessed() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> EcbRates.ratesIn(feed("1.1403", "0.8435"), "MAD", List.of("USD")));
        assertTrue(refused.getMessage().contains("MAD"), refused.getMessage());
        assertEquals(List.of("MAD"), EcbRates.missing(feed("1.1403", "0.8435"), "EUR", List.of("MAD", "USD")));
    }

    @Test
    void anything_unexpected_rejects_the_whole_feed() throws IOException {
        String real = realFeed();
        for (String broken : new String[] {
            "",
            "<html>Service unavailable</html>",
            real.replaceFirst("rate='[0-9.]+'", "rate='-1'"),
            real.replaceFirst("rate='[0-9.]+'", "rate='1,5'"),
            real.replaceFirst("time='", "time='2026-13-45' x='"),
            real.replace("</Cube>\n\t</Cube>", "<Cube time='2026-09-24'/></Cube>\n\t</Cube>"),
            real.replaceFirst("currency='USD'", "currency='usd'"),
        }) {
            assertThrows(IllegalArgumentException.class, () -> EcbRates.parse(broken), broken);
        }
    }

    @Test
    void the_feed_is_downloaded_over_http_and_a_refusal_is_an_error() throws Exception {
        String body = realFeed();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/daily.xml", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/gone", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(EcbRates.parse(body), EcbRates.parse(EcbRates.download(URI.create(base + "/daily.xml"))));
            IOException refused = assertThrows(IOException.class, () -> EcbRates.download(URI.create(base + "/gone")));
            assertTrue(refused.getMessage().contains("503"), refused.getMessage());
        } finally {
            server.stop(0);
        }
    }
}
