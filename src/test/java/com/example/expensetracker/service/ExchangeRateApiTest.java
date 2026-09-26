package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** ExchangeRate-API's answer: read as strictly as the bank's feed. */
class ExchangeRateApiTest {

    /** A copy of a real answer. */
    private static String realAnswer() throws IOException {
        try (InputStream in = ExchangeRateApiTest.class.getResourceAsStream("/exchangerate-api.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void a_real_answer_is_read_with_the_lek_and_the_euro_at_one() throws IOException {
        EcbRates.Feed feed = ExchangeRateApi.parse(realAnswer());
        assertTrue(feed.perEuro().size() > 100, "only " + feed.perEuro().size() + " rates");
        assertTrue(feed.perEuro().get("ALL").signum() > 0, "no lek");
        assertTrue(feed.perEuro().containsKey("MAD") && feed.perEuro().containsKey("DZD"));
        assertEquals(BigDecimal.ONE, feed.perEuro().get("EUR"));
        assertTrue(feed.day().isAfter(LocalDate.of(2026, 1, 1)));
    }

    @Test
    void anything_unexpected_rejects_the_whole_answer() throws IOException {
        String real = realAnswer();
        for (String broken : new String[] {
            "",
            "{\"result\":\"error\",\"error-type\":\"unsupported-code\"}",
            real.replace("\"base_code\":\"EUR\"", "\"base_code\":\"USD\""),
            real.replaceFirst("\"ALL\":[0-9.]+", "\"ALL\":-91.6"),
            real.replaceFirst("\"ALL\":[0-9.]+", "\"ALL\":\"91.6\""),
            real.replaceFirst("\"EUR\":1", "\"EUR\":2"),
            real.replaceFirst("\"time_last_update_unix\":\\d+", "\"time_last_update_unix\":\"soon\""),
        }) {
            org.junit.jupiter.api.Assertions.assertNotEquals(real, broken, "a case that breaks nothing");
            assertThrows(IllegalArgumentException.class, () -> ExchangeRateApi.parse(broken), broken);
        }
    }
}
