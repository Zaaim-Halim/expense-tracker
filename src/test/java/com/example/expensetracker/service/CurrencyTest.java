package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.Database;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Accounts in several currencies, rates, and totals that only ever add like with like. */
class CurrencyTest {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);

    @TempDir Path dir;
    private Database database;
    private LedgerService service;
    private Account euros;
    private Account dollars;
    private Account yen;

    @BeforeEach
    void open() throws SQLException {
        database = Database.open(dir.resolve("expenses.db"));
        service = new LedgerService(database);
        // Whatever this machine's region: the tests' base is the euro.
        service.changeBaseCurrency("EUR");
        euros = service.defaultAccount();
        dollars = service.save(new Account(0, "US bank", Account.Kind.BANK, "USD", 0));
        yen = service.save(new Account(0, "Tokyo wallet", Account.Kind.CASH, "JPY", 0));
    }

    @AfterEach
    void close() throws SQLException {
        database.close();
    }

    private Transaction expense(Account account, long minor, LocalDate day) throws SQLException {
        return Transaction.expense(account, minor, service.categoryNamed("Food"), "Lunch", day, "", List.of());
    }

    private void rate(String code, LocalDate from, String rate) throws SQLException {
        service.saveRate(new ExchangeRate(code, from, new BigDecimal(rate)));
    }

    @Test
    void an_amount_in_the_base_currency_is_its_own_conversion() throws SQLException {
        Transaction saved = service.save(expense(euros, 1250, SEP_1));
        assertEquals(1250, saved.baseAmountCents());
        assertEquals(0, saved.conversion().rate().compareTo(BigDecimal.ONE));
    }

    @Test
    void an_amount_in_another_currency_is_converted_at_the_rate_of_its_day() throws SQLException {
        rate("USD", SEP_1, "0.90");
        rate("USD", SEP_15, "0.95");
        assertEquals(900, service.save(expense(dollars, 1000, SEP_1)).baseAmountCents());
        assertEquals(900, service.save(expense(dollars, 1000, SEP_15.minusDays(1))).baseAmountCents(),
                "the latest rate on or before the day");
        assertEquals(950, service.save(expense(dollars, 1000, SEP_15)).baseAmountCents());
    }

    @Test
    void currencies_with_other_decimals_convert_exactly() throws SQLException {
        rate("JPY", SEP_1, "0.0062");
        Transaction ramen = service.save(expense(yen, 1250, SEP_1));
        assertEquals(775, ramen.baseAmountCents(), "1250 yen at 0.0062 is 7.75 euro");
        Account dinars = service.save(new Account(0, "Manama", Account.Kind.BANK, "BHD", 0));
        rate("BHD", SEP_1, "2.43");
        assertEquals(3000, service.save(expense(dinars, 12_345, SEP_1)).baseAmountCents(),
                "12.345 dinar at 2.43 is 29.99835 euro: 30.00");
    }

    @Test
    void with_no_rate_for_the_day_a_transaction_is_refused_unless_a_rate_is_given() throws SQLException {
        rate("USD", SEP_15, "0.95");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.save(expense(dollars, 1000, SEP_1)));
        assertTrue(refused.getMessage().contains("no rate for USD"), refused.getMessage());
        assertEquals(0, service.transactionCount(), "nothing was written");

        Transaction typed = expense(dollars, 1000, SEP_1);
        Transaction saved = service.save(new Transaction(0, typed.type(), typed.account(), typed.amountCents(), null,
                0, typed.category(), "", typed.description(), typed.date(), "", List.of(),
                new Transaction.Conversion(new BigDecimal("0.91"), 0)));
        assertEquals(910, saved.baseAmountCents(), "the rate typed with it");
    }

    @Test
    void a_rate_changed_later_never_rewrites_what_was_recorded() throws SQLException {
        rate("USD", SEP_1, "0.90");
        service.save(expense(dollars, 1000, SEP_15));
        rate("USD", SEP_1, "0.50");
        rate("USD", SEP_15, "2.00");
        Transaction kept = service.allTransactions().get(0);
        assertEquals(900, kept.baseAmountCents());
        assertEquals(0, kept.conversion().rate().compareTo(new BigDecimal("0.90")));
    }

    @Test
    void totals_add_only_amounts_in_the_base_currency() throws SQLException {
        rate("USD", SEP_1, "0.90");
        rate("JPY", SEP_1, "0.0062");
        service.save(expense(euros, 1000, SEP_1));
        service.save(expense(dollars, 1000, SEP_1));
        service.save(expense(yen, 1250, SEP_1));
        MonthSummary september = service.summary(YearMonth.of(2026, 9));
        assertEquals(1000 + 900 + 775, september.totalCents(), "not 1000 + 1000 + 1250");
        assertEquals(1000 + 900 + 775, service.expenseCountAndTotal()[1]);
        assertEquals(1000 + 900 + 775, september.byCategory().get(0).totalCents());

        TransactionFilter atLeastNine = new TransactionFilter(null, null, 0, 0, null, null, null, 900L, null);
        assertEquals(2, service.search(atLeastNine).size(), "amount filters compare amounts in the base currency");
    }

    @Test
    void balances_stay_in_each_accounts_currency_and_net_worth_converts_them() throws SQLException {
        Account euroSavings = service.save(new Account(0, "Savings", Account.Kind.SAVINGS, "EUR", 100_000));
        Account card = service.save(new Account(0, "US card", Account.Kind.CREDIT_CARD, "USD", -20_000));
        service.save(new Account(dollars.id(), dollars.name(), dollars.kind(), "USD", 50_000));
        rate("USD", SEP_1, "0.90");

        var balances = service.balances();
        assertEquals(50_000, balances.get(dollars.id()), "in dollars");
        assertEquals(-20_000, balances.get(card.id()));

        LedgerService.NetWorth worth = service.netWorth(SEP_15);
        assertEquals(100_000 + 45_000, worth.haveCents(), "1000 euro and 500 dollars at 0.90");
        assertEquals(18_000, worth.oweCents(), "200 dollars owed at 0.90");
        assertEquals(List.of(), worth.uncounted());
        assertEquals(euroSavings.currency(), "EUR");

        service.save(new Account(yen.id(), yen.name(), yen.kind(), "JPY", 10_000));
        assertEquals(List.of("JPY"), service.netWorth(SEP_15).uncounted(), "no rate: named, not guessed");
    }

    @Test
    void a_transfer_between_currencies_records_what_arrived() throws SQLException {
        rate("USD", SEP_1, "0.90");
        Transaction noArrival = new Transaction(0, Transaction.Type.TRANSFER, euros, 10_000, dollars, 0, null, "",
                "To the US", SEP_1, "", List.of());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.save(noArrival));
        assertTrue(refused.getMessage().contains("arrived"), refused.getMessage());

        service.save(new Transaction(0, Transaction.Type.TRANSFER, euros, 10_000, dollars, 10_870, null, "",
                "To the US", SEP_1, "", List.of()));
        assertEquals(-10_000, service.balances().get(euros.id()));
        assertEquals(10_870, service.balances().get(dollars.id()), "what the bank said arrived");
        assertEquals(0, service.summary(YearMonth.of(2026, 9)).totalCents(), "a transfer is still not spending");
    }

    @Test
    void an_accounts_currency_is_fixed_once_it_has_transactions() throws SQLException {
        rate("USD", SEP_1, "0.90");
        service.save(expense(dollars, 1000, SEP_1));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Account(dollars.id(), dollars.name(), dollars.kind(), "EUR", 0)));
        Account unused = service.save(new Account(0, "Spare", Account.Kind.CASH, "USD", 0));
        assertEquals("GBP", service.save(new Account(unused.id(), "Spare", Account.Kind.CASH, "GBP", 0)).currency());
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Account(0, "Nowhere", Account.Kind.CASH, "ZZZ", 0)), "an unknown currency");
    }

    @Test
    void the_base_currency_changes_only_while_nothing_depends_on_it() throws Exception {
        // Other currencies in use: refused.
        assertThrows(IllegalArgumentException.class, () -> service.changeBaseCurrency("GBP"));
        try (Database fresh = Database.open(dir.resolve("fresh.db"))) {
            LedgerService other = new LedgerService(fresh);
            other.changeBaseCurrency("EUR");
            other.changeBaseCurrency("GBP");
            assertEquals("GBP", other.baseCurrency().code());
            assertEquals("GBP", other.defaultAccount().currency(), "the accounts in it are renamed with it");
            other.save(expense(other, other.defaultAccount()));
            assertThrows(IllegalArgumentException.class, () -> other.changeBaseCurrency("JPY"),
                    "other decimals would change the amounts recorded");
            other.changeBaseCurrency("USD");
            other.saveRate(new ExchangeRate("EUR", SEP_1, new BigDecimal("1.1")));
            assertThrows(IllegalArgumentException.class, () -> other.changeBaseCurrency("GBP"),
                    "rates are in the base currency");
        }
    }

    @Test
    void an_opening_balance_keeps_its_value_when_the_base_currency_changes() throws Exception {
        try (Database fresh = Database.open(dir.resolve("opening.db"))) {
            LedgerService other = new LedgerService(fresh);
            other.changeBaseCurrency("EUR");
            Account main = other.defaultAccount();
            other.save(new Account(main.id(), main.name(), main.kind(), "EUR", 10_000));
            assertThrows(IllegalArgumentException.class, () -> other.changeBaseCurrency("JPY"),
                    "100.00 euro must not become 10000 yen");
            assertThrows(IllegalArgumentException.class, () -> other.changeBaseCurrency("BHD"));
            assertEquals("EUR", other.baseCurrency().code());
            assertEquals(10_000, other.balances().get(main.id()));
            other.changeBaseCurrency("GBP");
            assertEquals(10_000, other.balances().get(main.id()), "same decimals: a new name only");
        }
    }

    private static Transaction expense(LedgerService other, Account account) throws SQLException {
        return Transaction.expense(account, 500, other.categoryNamed("Food"), "Tea", SEP_1, "", List.of());
    }

    @Test
    void a_custom_currency_has_its_own_decimals_and_is_kept_while_in_use() throws SQLException {
        CurrencyUnit points = service.saveCustomCurrency(new CurrencyUnit("pts", " Air miles ", 0, true));
        assertEquals("PTS", points.code());
        assertEquals("Air miles", points.name());
        assertEquals(0, service.currency("PTS").digits());
        assertThrows(IllegalArgumentException.class,
                () -> service.saveCustomCurrency(new CurrencyUnit("USD", "Mine", 2, true)), "ISO's own");
        assertThrows(IllegalArgumentException.class,
                () -> service.saveCustomCurrency(new CurrencyUnit("X1", "Bad", 2, true)));

        Account miles = service.save(new Account(0, "Miles", Account.Kind.INVESTMENT, "PTS", 12_000));
        assertThrows(IllegalArgumentException.class, () -> service.deleteCustomCurrency(points));
        assertThrows(IllegalArgumentException.class,
                () -> service.saveCustomCurrency(new CurrencyUnit("PTS", "Air miles", 2, true)),
                "its decimals are fixed once in use");
        rate("PTS", SEP_1, "0.01");
        assertEquals(120_00, service.netWorth(SEP_15).haveCents() - 0, "12000 miles at 0.01 is 120.00 euro");
        assertEquals(miles.currency(), "PTS");
    }

    @Test
    void the_converter_goes_through_the_base_currency() throws SQLException {
        rate("USD", SEP_1, "0.90");
        rate("JPY", SEP_1, "0.0062");
        assertEquals(900, service.convert(1000, "USD", "EUR", SEP_15));
        assertEquals(1000, service.convert(900, "EUR", "USD", SEP_15));
        // 10 dollars = 9 euro = 1451.6129 yen.
        assertEquals(1452, service.convert(1000, "USD", "JPY", SEP_15));
        assertThrows(IllegalArgumentException.class, () -> service.convert(1000, "USD", "EUR", SEP_1.minusDays(1)));
    }

    @Test
    void a_rate_for_the_base_currency_or_a_bad_rate_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> rate("EUR", SEP_1, "1"));
        assertThrows(IllegalArgumentException.class, () -> rate("USD", SEP_1, "0"));
        assertThrows(IllegalArgumentException.class, () -> rate("USD", SEP_1, "-1"));
    }

    private Transaction priced(Account account, long charged, String currency, long price, Transaction.Type type)
            throws SQLException {
        return new Transaction(0, type, account, charged, null, 0,
                service.categoryNamed(type == Transaction.Type.INCOME ? "Salary" : "Food"), "", "Priced", SEP_15,
                "", List.of(), null, new Transaction.Original(currency, price));
    }

    @Test
    void a_price_in_another_currency_is_kept_beside_what_the_account_was_charged() throws SQLException {
        Transaction saved = service.save(priced(euros, 4_470, "USD", 4_850, Transaction.Type.EXPENSE));
        assertEquals(new Transaction.Original("USD", 4_850), saved.original());
        Transaction read = service.allTransactions().get(0);
        assertEquals(new Transaction.Original("USD", 4_850), read.original());
        assertEquals(4_470, read.amountCents());
        assertEquals(4_470, read.baseAmountCents(), "the euro account's charge, not the dollar price");
        assertEquals(-4_470, service.balances().get(euros.id()), "balances use what was charged");
        assertEquals(4_470, service.summary(YearMonth.of(2026, 9)).totalCents(), "and so do totals");
        assertEquals(4_470, service.netWorth(SEP_15).oweCents());
    }

    @Test
    void a_price_in_the_accounts_own_currency_is_not_kept_twice() throws SQLException {
        assertEquals(null, service.save(priced(euros, 1_000, "EUR", 1_000, Transaction.Type.EXPENSE)).original());
    }

    @Test
    void a_price_in_the_base_currency_on_a_foreign_account_is_still_converted_from_the_charge() throws SQLException {
        // 40.00 euro paid with the dollar card, charged 43.50 dollars: the
        // base amount comes from the charge at the day's rate, one rule for all.
        rate("USD", SEP_1, "0.92");
        Transaction saved = service.save(priced(dollars, 4_350, "EUR", 4_000, Transaction.Type.EXPENSE));
        assertEquals(4_002, saved.baseAmountCents(), "43.50 at 0.92 = 40.02");
        assertEquals(new Transaction.Original("EUR", 4_000), saved.original());
    }

    @Test
    void a_transfer_or_a_bad_price_is_refused() throws SQLException {
        Transaction transfer = new Transaction(0, Transaction.Type.TRANSFER, euros, 1_000, dollars, 1_080, null, "",
                "Move", SEP_15, "", List.of(), null, new Transaction.Original("GBP", 900));
        assertThrows(IllegalArgumentException.class, () -> service.save(transfer));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(priced(euros, 1_000, "USD", 0, Transaction.Type.EXPENSE)));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(priced(euros, 1_000, "ZZZ", 100, Transaction.Type.EXPENSE)));
        assertEquals(0, service.transactionCount());
    }

    @Test
    void a_custom_currency_a_price_is_in_is_kept_with_its_decimals() throws SQLException {
        CurrencyUnit points = service.saveCustomCurrency(new CurrencyUnit("PTS", "Air miles", 0, true));
        service.save(priced(euros, 1_200, "PTS", 1_500, Transaction.Type.EXPENSE));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.deleteCustomCurrency(points));
        assertTrue(refused.getMessage().contains("price"), refused.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> service.saveCustomCurrency(new CurrencyUnit("PTS", "Air miles", 2, true)),
                "1500 miles must not become 15.00");
    }

    @Test
    void editing_keeps_or_drops_the_price_and_duplicating_keeps_it() throws SQLException {
        Transaction saved = service.save(priced(euros, 4_470, "USD", 4_850, Transaction.Type.EXPENSE));
        Transaction copy = service.duplicate(saved);
        assertEquals(saved.original(), copy.original());
        service.save(new Transaction(saved.id(), saved.type(), saved.account(), 5_000, null, 0, saved.category(), "",
                saved.description(), saved.date(), "", List.of(), null, null));
        assertEquals(null, service.allTransactions().stream().filter(t -> t.id() == saved.id())
                .findFirst().orElseThrow().original(), "an edit without a price clears it");
    }

    private static EcbRates.Feed feed(LocalDate day, String usd) {
        return new EcbRates.Feed(day, java.util.Map.of("EUR", BigDecimal.ONE, "USD", new BigDecimal(usd),
                "JPY", new BigDecimal("179.70"), "GBP", new BigDecimal("0.8435")));
    }

    @Test
    void fetched_rates_fill_the_days_that_have_none_and_never_replace_the_users() throws SQLException {
        rate("USD", SEP_15, "0.95");
        EcbRates.Result result = service.keepRates(feed(SEP_15, "1.25"));
        assertEquals(1, result.added(), "JPY only: USD already had the user's rate for that day");
        assertEquals(new BigDecimal("0.95"), service.rateOn("USD", SEP_15).orElseThrow().rate());
        assertEquals(ExchangeRate.Source.MANUAL, service.rateOn("USD", SEP_15).orElseThrow().source());
        assertEquals(ExchangeRate.Source.ECB, service.rateOn("JPY", SEP_15).orElseThrow().source());

        EcbRates.Result later = service.keepRates(feed(SEP_15.plusDays(1), "1.25"));
        assertEquals(2, later.added());
        assertEquals(new BigDecimal("0.8"), service.rateOn("USD", SEP_15.plusDays(1)).orElseThrow().rate());
        assertEquals(0, service.keepRates(feed(SEP_15.plusDays(1), "1.30")).added(), "a day is fetched once");
    }

    @Test
    void the_currencies_in_use_include_the_ones_prices_were_paid_in() throws SQLException {
        service.save(new Transaction(0, Transaction.Type.EXPENSE, euros, 2_988, null, 0,
                service.categoryNamed("Food"), "", "Lunch in London", SEP_15, "", List.of(), null,
                new Transaction.Original("GBP", 2_500)));
        assertEquals(List.of("GBP", "JPY", "USD"), service.currenciesInUse());
        EcbRates.Result result = service.keepRates(feed(SEP_15, "1.25"));
        assertEquals(3, result.added());
        assertEquals(List.of(), result.missing());
    }

    @Test
    void a_currency_the_feed_does_not_publish_is_named() throws SQLException {
        service.save(new Account(0, "Rabat", Account.Kind.BANK, "MAD", 0));
        assertEquals(List.of("MAD"), service.keepRates(feed(SEP_15, "1.25")).missing());
    }

    @Test
    void fetched_rates_never_lock_the_base_currency_and_the_users_still_do() throws Exception {
        try (Database fresh = Database.open(dir.resolve("fetched.db"))) {
            LedgerService other = new LedgerService(fresh);
            other.changeBaseCurrency("EUR");
            other.saveCustomCurrency(new CurrencyUnit("PTS", "Points", 0, true));
            other.saveRate(new ExchangeRate("PTS", SEP_1, new BigDecimal("0.01")));
            assertThrows(IllegalArgumentException.class, () -> other.changeBaseCurrency("GBP"),
                    "the user's own rate is in euros");
            other.deleteRate(other.rates().get(0));

            // A price paid in dollars is what makes the dollar wanted.
            other.save(new Transaction(0, Transaction.Type.EXPENSE, other.defaultAccount(), 900, null, 0,
                    other.categoryNamed("Food"), "", "Coffee in New York", SEP_1, "", List.of(), null,
                    new Transaction.Original("USD", 1_125)));
            assertEquals(1, other.keepRates(new EcbRates.Feed(SEP_1, java.util.Map.of("EUR", BigDecimal.ONE,
                    "USD", new BigDecimal("1.25")))).added());
            assertEquals(1, other.rates().size(), "a fetched rate to be discarded");
            other.changeBaseCurrency("GBP");
            assertEquals("GBP", other.baseCurrency().code());
            assertEquals(List.of(), other.rates(), "rates in the old base were fetched, and are gone");
        }
    }

    /** What the second feed says: the lek among others, per euro. */
    private static EcbRates.Feed wider(LocalDate day) {
        return new EcbRates.Feed(day, java.util.Map.of("EUR", BigDecimal.ONE, "USD", new BigDecimal("1.14"),
                "ALL", new BigDecimal("91.6"), "JPY", new BigDecimal("180")));
    }

    @Test
    void each_currency_comes_from_the_bank_when_it_can_and_from_the_other_feed_otherwise() throws SQLException {
        service.save(new Account(0, "Tirana", Account.Kind.BANK, "ALL", 0));
        EcbRates.Result result = service.keepRates(feed(SEP_15, "1.25"), () -> wider(SEP_15));
        assertEquals(3, result.added());
        assertEquals(List.of(), result.missing());
        assertEquals(ExchangeRate.Source.ECB, service.rateOn("USD", SEP_15).orElseThrow().source());
        assertEquals(new BigDecimal("0.8"), service.rateOn("USD", SEP_15).orElseThrow().rate(), "the bank's, not 1/1.14");
        ExchangeRate lek = service.rateOn("ALL", SEP_15).orElseThrow();
        assertEquals(ExchangeRate.Source.EXCHANGE_RATE_API, lek.source());
        assertEquals(new BigDecimal("0.0109170306"), lek.rate(), "1 / 91.6");
    }

    @Test
    void a_base_the_bank_does_not_publish_takes_every_rate_from_the_other_feed() throws Exception {
        try (Database fresh = Database.open(dir.resolve("lek.db"))) {
            LedgerService other = new LedgerService(fresh);
            other.changeBaseCurrency("ALL");
            other.save(new Account(0, "Savings", Account.Kind.SAVINGS, "EUR", 0));
            other.save(new Account(0, "US card", Account.Kind.CREDIT_CARD, "USD", 0));
            EcbRates.Result result = other.keepRates(feed(SEP_15, "1.25"), () -> wider(SEP_15));
            assertEquals(2, result.added());
            assertEquals(new BigDecimal("91.6"), other.rateOn("EUR", SEP_15).orElseThrow().rate());
            assertEquals(new BigDecimal("80.350877193"), other.rateOn("USD", SEP_15).orElseThrow().rate(),
                    "91.6 / 1.14 from the one feed, never the bank's dollar with the other's lek");
            assertTrue(other.rates().stream().allMatch(r -> r.source() == ExchangeRate.Source.EXCHANGE_RATE_API));
        }
    }

    @Test
    void the_other_feed_is_never_asked_when_the_bank_covers_everything() throws SQLException {
        boolean[] asked = {false};
        EcbRates.Result result = service.keepRates(feed(SEP_15, "1.25"), () -> {
            asked[0] = true;
            return wider(SEP_15);
        });
        assertEquals(2, result.added());
        assertTrue(!asked[0], "a second site was contacted for nothing");
        assertTrue(!service.needsMoreThan(feed(SEP_15, "1.25")));
    }

    @Test
    void when_the_other_feed_fails_the_banks_rates_are_still_kept_and_the_reason_given() throws SQLException {
        service.save(new Account(0, "Tirana", Account.Kind.BANK, "ALL", 0));
        assertTrue(service.needsMoreThan(feed(SEP_15, "1.25")));
        EcbRates.Result result = service.keepRates(feed(SEP_15, "1.25"), () -> {
            throw new java.io.IOException("open.er-api.com answered 503");
        });
        assertEquals(2, result.added(), "USD and JPY from the bank");
        assertEquals(List.of("ALL"), result.missing());
        assertTrue(result.problem().contains("503"), result.problem());
    }
}
