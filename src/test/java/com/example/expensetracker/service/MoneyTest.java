package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void reads_amounts_the_way_people_type_them() {
        assertEquals(1200, Money.parseCents("12"));
        assertEquals(1250, Money.parseCents("12.5"));
        assertEquals(1250, Money.parseCents("12,50"));
        assertEquals(123456, Money.parseCents("1 234.56"));
        assertEquals(123456, Money.parseCents("1,234.56"));
        assertEquals(1, Money.parseCents("0.01"));
    }

    @Test
    void refuses_what_is_not_a_positive_amount_in_cents() {
        for (String bad : new String[] {"", "  ", "abc", "0", "-5", "1.234", "12.5.1"}) {
            assertThrows(IllegalArgumentException.class, () -> Money.parseCents(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Money.parseCents("100000001"));
    }

    @Test
    void writes_two_decimals_in_the_given_locale() {
        assertEquals("1,234.50", Money.format(123450, Locale.US));
        assertEquals("0.05", Money.format(5, Locale.US));
        assertEquals("1234.50", Money.plain(123450));
    }

    @Test
    void reads_an_amount_with_as_many_decimals_as_its_currency_has() {
        assertEquals(1250, Money.parse("1250", 0), "yen have no minor unit");
        assertThrows(IllegalArgumentException.class, () -> Money.parse("12.5", 0));
        assertEquals(12_500, Money.parse("12.5", 3), "fils: three digits");
        assertEquals(12_345, Money.parse("12.345", 3));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("12.3456", 3));
        assertEquals(1_000_000_000L, Money.parse("1000000", 3), "the ceiling is in whole units");
        assertThrows(IllegalArgumentException.class, () -> Money.parse("10000001", 0));
    }

    @Test
    void writes_an_amount_with_its_currencys_decimals() {
        assertEquals("1,250", Money.format(1250, 0, Locale.US));
        assertEquals("12.345", Money.format(12_345, 3, Locale.US));
        assertEquals("1250", Money.plain(1250, 0));
        assertEquals("12.500", Money.plain(12_500, 3));
    }

    @Test
    void converts_between_currencies_with_different_decimals_rounding_half_to_even() {
        java.math.BigDecimal yenInEuro = new java.math.BigDecimal("0.0062");
        // 1250 yen at 0.0062 = 7.75 euro exactly.
        assertEquals(775, Money.convert(1250, 0, yenInEuro, 2));
        // 12.345 dinar at 2.43 = 29.99835 euro: 30.00.
        assertEquals(3000, Money.convert(12_345, 3, new java.math.BigDecimal("2.43"), 2));
        // Exactly half a cent goes to the even neighbour, both ways.
        assertEquals(2, Money.convert(5, 2, new java.math.BigDecimal("0.5"), 2), "0.025 -> 0.02");
        assertEquals(4, Money.convert(7, 2, new java.math.BigDecimal("0.5"), 2), "0.035 -> 0.04");
        // Euro to yen: 7.75 euro at 161.29 = 1249.9975 yen: 1250.
        assertEquals(1250, Money.convert(775, 2, new java.math.BigDecimal("161.29"), 0));
    }

    @Test
    void reads_a_rate_exactly_and_refuses_what_is_not_one() {
        assertEquals(new java.math.BigDecimal("0.92"), Money.parseRate("0.920"));
        assertEquals(new java.math.BigDecimal("10.5"), Money.parseRate("10,5"));
        for (String bad : new String[] {"", "0", "-1", "abc", "0.00000000001"}) {
            assertThrows(IllegalArgumentException.class, () -> Money.parseRate(bad), bad);
        }
    }
}
