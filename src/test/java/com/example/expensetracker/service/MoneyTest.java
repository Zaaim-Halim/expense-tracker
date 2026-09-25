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
}
