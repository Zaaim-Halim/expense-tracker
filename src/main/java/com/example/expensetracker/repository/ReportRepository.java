package com.example.expensetracker.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Totals for reports and the calendar, added up by the database in the base
 * currency: one query per report, however many transactions there are.
 */
public final class ReportRepository {

    private final Connection connection;

    public ReportRepository(Database database) {
        this.connection = database.connection();
    }

    /**
     * What came in and went out on one day or in one month, in the base
     * currency's minor units. Transfers are neither.
     *
     * @param incomeCents everything received
     * @param spentCents  everything spent
     * @param count       how many expenses and incomes
     */
    public record Totals(long incomeCents, long spentCents, int count) {
        public static final Totals NONE = new Totals(0, 0, 0);
    }

    /** Totals for each month between two days inclusive that has any, in order. */
    public Map<YearMonth, Totals> byMonth(LocalDate from, LocalDate to) throws SQLException {
        Map<YearMonth, Totals> found = new TreeMap<>();
        for (var entry : grouped("substr(occurred_on, 1, 7)", from, to).entrySet()) {
            found.put(YearMonth.parse(entry.getKey()), entry.getValue());
        }
        return found;
    }

    /** Totals for each day between two days inclusive that has any, in order. */
    public Map<LocalDate, Totals> byDay(LocalDate from, LocalDate to) throws SQLException {
        Map<LocalDate, Totals> found = new TreeMap<>();
        for (var entry : grouped("occurred_on", from, to).entrySet()) {
            found.put(LocalDate.parse(entry.getKey()), entry.getValue());
        }
        return found;
    }

    private Map<String, Totals> grouped(String key, LocalDate from, LocalDate to) throws SQLException {
        Map<String, Totals> found = new TreeMap<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT " + key + " AS k, "
                + "SUM(CASE type WHEN 'income' THEN base_amount_cents ELSE 0 END), "
                + "SUM(CASE type WHEN 'expense' THEN base_amount_cents ELSE 0 END), COUNT(*) "
                + "FROM transactions WHERE type <> 'transfer' AND occurred_on BETWEEN ? AND ? "
                + "GROUP BY k ORDER BY k")) {
            query.setString(1, from.toString());
            query.setString(2, to.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    found.put(rows.getString(1), new Totals(rows.getLong(2), rows.getLong(3), rows.getInt(4)));
                }
            }
        }
        return found;
    }

    /**
     * Who was paid the most between two days inclusive.
     *
     * @param merchant   the name, as first written
     * @param spentCents what they were paid, in the base currency
     * @param count      how many times
     */
    public record Merchant(String merchant, long spentCents, int count) {
    }

    /** The {@code limit} merchants paid the most, most first; names are matched ignoring case. */
    public List<Merchant> topMerchants(LocalDate from, LocalDate to, int limit) throws SQLException {
        List<Merchant> found = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT MIN(merchant), SUM(base_amount_cents) AS spent, COUNT(*)
                FROM transactions
                WHERE type = 'expense' AND merchant <> '' AND occurred_on BETWEEN ? AND ?
                GROUP BY merchant COLLATE NOCASE
                ORDER BY spent DESC, MIN(merchant) COLLATE NOCASE
                LIMIT ?""")) {
            query.setString(1, from.toString());
            query.setString(2, to.toString());
            query.setInt(3, limit);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    found.add(new Merchant(rows.getString(1), rows.getLong(2), rows.getInt(3)));
                }
            }
        }
        return found;
    }

    /** The day of the first transaction, if there is one. */
    public LocalDate firstDay() throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT MIN(occurred_on) FROM transactions");
                ResultSet rows = query.executeQuery()) {
            String day = rows.next() ? rows.getString(1) : null;
            return day == null ? null : LocalDate.parse(day);
        }
    }

    /**
     * What went out of, and came into, one account between two days, in the
     * base currency. Transfers are neither.
     *
     * @param account     its name
     * @param kind        its kind's stored name
     * @param spentCents  expenses paid from it
     * @param incomeCents income received into it
     */
    public record AccountTotals(String account, String kind, long spentCents, long incomeCents) {
    }

    /** Each account with any income or spending between two days, most spent from first. */
    public List<AccountTotals> byAccount(LocalDate from, LocalDate to) throws SQLException {
        List<AccountTotals> found = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT a.name, a.kind,
                       SUM(CASE t.type WHEN 'expense' THEN t.base_amount_cents ELSE 0 END) AS spent,
                       SUM(CASE t.type WHEN 'income' THEN t.base_amount_cents ELSE 0 END) AS income
                FROM transactions t JOIN accounts a ON a.id = t.account_id
                WHERE t.type <> 'transfer' AND t.occurred_on BETWEEN ? AND ?
                GROUP BY a.id ORDER BY spent DESC, a.name COLLATE NOCASE""")) {
            query.setString(1, from.toString());
            query.setString(2, to.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    found.add(new AccountTotals(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getLong(4)));
                }
            }
        }
        return found;
    }

    /**
     * One of the largest expenses.
     *
     * @param description what it was
     * @param merchant    who was paid, possibly empty
     * @param day         when
     * @param category    its category's name
     * @param color       its category's colour
     * @param cents       what it cost, in the base currency
     */
    public record Expense(String description, String merchant, LocalDate day, String category, String color,
            long cents) {
    }

    /** The {@code limit} largest expenses between two days, in the base currency, largest first. */
    public List<Expense> largestExpenses(LocalDate from, LocalDate to, int limit) throws SQLException {
        List<Expense> found = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT t.description, t.merchant, t.occurred_on, c.name, c.color, t.base_amount_cents
                FROM transactions t JOIN categories c ON c.id = t.category_id
                WHERE t.type = 'expense' AND t.occurred_on BETWEEN ? AND ?
                ORDER BY t.base_amount_cents DESC, t.occurred_on DESC, t.id DESC
                LIMIT ?""")) {
            query.setString(1, from.toString());
            query.setString(2, to.toString());
            query.setInt(3, limit);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    found.add(new Expense(rows.getString(1), rows.getString(2), LocalDate.parse(rows.getString(3)),
                            rows.getString(4), rows.getString(5), rows.getLong(6)));
                }
            }
        }
        return found;
    }

    /** What came in and went out between two days, in the base currency. */
    public Totals between(LocalDate from, LocalDate to) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COALESCE(SUM(CASE type WHEN 'income' THEN base_amount_cents ELSE 0 END), 0),
                       COALESCE(SUM(CASE type WHEN 'expense' THEN base_amount_cents ELSE 0 END), 0), COUNT(*)
                FROM transactions WHERE type <> 'transfer' AND occurred_on BETWEEN ? AND ?""")) {
            query.setString(1, from.toString());
            query.setString(2, to.toString());
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return new Totals(rows.getLong(1), rows.getLong(2), rows.getInt(3));
            }
        }
    }
}
