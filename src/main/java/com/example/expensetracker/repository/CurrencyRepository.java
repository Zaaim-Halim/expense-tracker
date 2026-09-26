package com.example.expensetracker.repository;

import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.model.ExchangeRate;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The base currency, the currencies the user defined, and exchange rates. */
public final class CurrencyRepository {

    private final Connection connection;

    public CurrencyRepository(Database database) {
        this.connection = database.connection();
    }

    /** The code of the currency every total is in. */
    public String baseCode() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT value FROM meta WHERE key = 'base_currency'")) {
            return rows.next() ? rows.getString(1) : Database.FALLBACK_BASE;
        }
    }

    /**
     * Makes {@code code} the base currency, and every account that was in the
     * old one in the new one: only ever called while every account is in the
     * base currency, so nothing is converted, only named.
     */
    public void changeBase(String code) throws SQLException {
        String old = baseCode();
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement meta = connection.prepareStatement(
                        "UPDATE meta SET value = ? WHERE key = 'base_currency'");
                PreparedStatement accounts = connection.prepareStatement(
                        "UPDATE accounts SET currency = ? WHERE currency = ?")) {
            meta.setString(1, code);
            meta.executeUpdate();
            accounts.setString(1, code);
            accounts.setString(2, old);
            accounts.executeUpdate();
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /** The currencies the user defined, by code. */
    public List<CurrencyUnit> customCurrencies() throws SQLException {
        List<CurrencyUnit> found = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT code, name, digits FROM currencies ORDER BY code")) {
            while (rows.next()) {
                found.add(new CurrencyUnit(rows.getString(1), rows.getString(2), rows.getInt(3), true));
            }
        }
        return found;
    }

    public void saveCustom(CurrencyUnit currency) throws SQLException {
        try (PreparedStatement save = connection.prepareStatement(
                "INSERT OR REPLACE INTO currencies(code, name, digits) VALUES (?, ?, ?)")) {
            save.setString(1, currency.code());
            save.setString(2, currency.name());
            save.setInt(3, currency.digits());
            save.executeUpdate();
        }
    }

    public void deleteCustom(String code) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM currencies WHERE code = ?")) {
            delete.setString(1, code);
            delete.executeUpdate();
        }
    }

    /** How many accounts are in {@code code}. */
    public int accountsIn(String code) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM accounts WHERE currency = ?")) {
            query.setString(1, code);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** Every rate, by currency and then newest first. */
    public List<ExchangeRate> rates() throws SQLException {
        List<ExchangeRate> found = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT currency, effective_on, rate, source FROM exchange_rates ORDER BY currency, effective_on DESC")) {
            while (rows.next()) {
                found.add(read(rows));
            }
        }
        return found;
    }

    /** The rate for {@code currency} on {@code day}: the latest dated that day or before. */
    public Optional<ExchangeRate> rateOn(String currency, LocalDate day) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT currency, effective_on, rate, source FROM exchange_rates
                WHERE currency = ? AND effective_on <= ?
                ORDER BY effective_on DESC LIMIT 1""")) {
            query.setString(1, currency);
            query.setString(2, day.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    /**
     * Adds a rate, or replaces the one for the same currency and day. The
     * source is written out, never left to the column's default.
     */
    public void saveRate(ExchangeRate rate) throws SQLException {
        try (PreparedStatement save = connection.prepareStatement(
                "INSERT OR REPLACE INTO exchange_rates(currency, effective_on, rate, source) VALUES (?, ?, ?, ?)")) {
            bind(save, rate);
            save.executeUpdate();
        }
    }

    /**
     * Adds fetched rates for days that have none, all or nothing. A rate
     * already there, the user's above all, is kept.
     *
     * @return how many were added
     */
    public int addFetched(java.util.List<ExchangeRate> rates) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement add = connection.prepareStatement(
                "INSERT OR IGNORE INTO exchange_rates(currency, effective_on, rate, source) VALUES (?, ?, ?, ?)")) {
            int added = 0;
            for (ExchangeRate rate : rates) {
                bind(add, rate);
                added += add.executeUpdate();
            }
            connection.commit();
            return added;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /** Removes every rate a feed supplied; the user's own stay. */
    public int deleteFetched() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("DELETE FROM exchange_rates WHERE source <> 'manual'");
        }
    }

    /** How many rates the user entered. */
    public int manualRateCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM exchange_rates WHERE source = 'manual'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** The currencies transactions were priced in, other than their accounts'. */
    public java.util.List<String> priceCurrencies() throws SQLException {
        java.util.List<String> found = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT DISTINCT original_currency FROM transactions
                        WHERE original_currency IS NOT NULL ORDER BY 1""")) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        return found;
    }

    /** The currencies accounts are in. */
    public java.util.List<String> accountCurrencies() throws SQLException {
        java.util.List<String> found = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT DISTINCT currency FROM accounts ORDER BY 1")) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        return found;
    }

    private static void bind(PreparedStatement statement, ExchangeRate rate) throws SQLException {
        statement.setString(1, rate.currency());
        statement.setString(2, rate.effectiveOn().toString());
        statement.setString(3, rate.rate().stripTrailingZeros().toPlainString());
        statement.setString(4, rate.source().key());
    }

    public void deleteRate(ExchangeRate rate) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM exchange_rates WHERE currency = ? AND effective_on = ?")) {
            delete.setString(1, rate.currency());
            delete.setString(2, rate.effectiveOn().toString());
            delete.executeUpdate();
        }
    }

    /** How many rates there are, for any currency. */
    public int rateCount() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM exchange_rates")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static ExchangeRate read(ResultSet rows) throws SQLException {
        return new ExchangeRate(rows.getString(1), LocalDate.parse(rows.getString(2)), new BigDecimal(rows.getString(3)),
                ExchangeRate.Source.fromKey(rows.getString(4)));
    }
}
