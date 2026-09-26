package com.example.expensetracker.service;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Budget;
import com.example.expensetracker.model.Recurring;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.CategoryTotal;
import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.AccountRepository;
import com.example.expensetracker.repository.CategoryRepository;
import com.example.expensetracker.repository.BudgetRepository;
import com.example.expensetracker.repository.CurrencyRepository;
import com.example.expensetracker.repository.RecurringRepository;
import com.example.expensetracker.repository.Database;
import com.example.expensetracker.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Everything the application does with accounts, transactions and
 * categories.
 *
 * <p>The rules live here, so the window and the command line apply the same
 * ones. A rule broken by the user's input is an {@link IllegalArgumentException}
 * with a message meant for them; a failure of the database is an
 * {@link SQLException}.
 */
public final class LedgerService {

    public static final int MAX_DESCRIPTION = 120;
    public static final int MAX_MERCHANT = 80;
    public static final int MAX_NOTE = 500;
    public static final int MAX_CATEGORY_NAME = 40;
    public static final int MAX_ACCOUNT_NAME = 40;
    public static final int MAX_TAGS = 10;
    public static final int MAX_TAG_NAME = 30;

    public static final int MAX_CURRENCY_NAME = 40;

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    /** A custom currency's code: three to five capital letters, like ISO's own. */
    private static final Pattern CUSTOM_CODE = Pattern.compile("[A-Z]{3,5}");

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final CurrencyRepository currencies;
    private final BudgetRepository budgets;
    private final RecurringRepository recurring;

    public LedgerService(Database database) {
        this.transactions = new TransactionRepository(database);
        this.accounts = new AccountRepository(database);
        this.categories = new CategoryRepository(database);
        this.currencies = new CurrencyRepository(database);
        this.budgets = new BudgetRepository(database);
        this.recurring = new RecurringRepository(database);
    }

    // --- transactions ---------------------------------------------------------

    public List<Transaction> allTransactions() throws SQLException {
        return transactions.findAll();
    }

    public List<Transaction> recentTransactions(int limit) throws SQLException {
        return transactions.findRecent(limit);
    }

    /** The transactions {@code filter} lets through, newest first. */
    public List<Transaction> search(TransactionFilter filter) throws SQLException {
        return transactions.findAll().stream().filter(filter::matches).toList();
    }

    /** Every tag some transaction carries, alphabetically. */
    public List<String> allTags() throws SQLException {
        return transactions.allTags();
    }

    /** Saves a new transaction, or changes an existing one (a non-zero id). */
    public Transaction save(Transaction transaction) throws SQLException {
        Transaction valid = validate(transaction);
        if (valid.id() == 0) {
            return transactions.insert(valid);
        }
        transactions.update(valid);
        return valid;
    }

    /**
     * A copy of {@code transaction}, dated today, saved as a new one: at
     * today's rate, or at the original's when there is no rate for today.
     */
    public Transaction duplicate(Transaction transaction) throws SQLException {
        Transaction copy = transaction.duplicate(LocalDate.now());
        String code = transaction.account().currency();
        if (!code.equals(baseCurrency().code()) && currencies.rateOn(code, copy.date()).isEmpty()
                && transaction.conversion() != null) {
            copy = new Transaction(0, copy.type(), copy.account(), copy.amountCents(), copy.toAccount(),
                    copy.toAmountCents(), copy.category(), copy.merchant(), copy.description(), copy.date(),
                    copy.note(), copy.tags(), transaction.conversion(), copy.original());
        }
        return save(copy);
    }

    public void deleteTransaction(long id) throws SQLException {
        transactions.delete(id);
    }

    /** How many expenses there are, and what they add up to, in cents. */
    public long[] expenseCountAndTotal() throws SQLException {
        return transactions.expenseCountAndTotal();
    }

    /** How many transactions there are, of every type. */
    public long transactionCount() throws SQLException {
        return transactions.count();
    }

    /** What came in and went out in {@code month}, and where it went. */
    public MonthSummary summary(YearMonth month) throws SQLException {
        List<CategoryTotal> spent = transactions.totalsByCategory(Transaction.Type.EXPENSE,
                month.atDay(1), month.atEndOfMonth());
        List<CategoryTotal> received = transactions.totalsByCategory(Transaction.Type.INCOME,
                month.atDay(1), month.atEndOfMonth());
        long total = spent.stream().mapToLong(CategoryTotal::totalCents).sum();
        int count = spent.stream().mapToInt(CategoryTotal::count).sum();
        long income = received.stream().mapToLong(CategoryTotal::totalCents).sum();
        return new MonthSummary(month, total, count, spent, income);
    }

    private Transaction validate(Transaction t) throws SQLException {
        if (t.type() == null) {
            throw new IllegalArgumentException("Choose whether it is an expense, income or a transfer");
        }
        String description = t.description() == null ? "" : t.description().strip();
        if (description.isEmpty()) {
            throw new IllegalArgumentException("Describe it");
        }
        if (description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("Keep the description under " + MAX_DESCRIPTION + " characters");
        }
        String merchant = t.merchant().strip();
        if (merchant.length() > MAX_MERCHANT) {
            throw new IllegalArgumentException("Keep the merchant under " + MAX_MERCHANT + " characters");
        }
        if (t.account() == null || t.account().id() == 0) {
            throw new IllegalArgumentException("Choose an account");
        }
        CurrencyUnit currency = currency(t.account().currency());
        if (t.amountCents() <= 0) {
            throw new IllegalArgumentException("The amount must be more than zero");
        }
        if (t.amountCents() > largest(currency)) {
            throw new IllegalArgumentException("That amount is too large");
        }
        if (t.date() == null) {
            throw new IllegalArgumentException("Choose a date");
        }
        String note = t.note().strip();
        if (note.length() > MAX_NOTE) {
            throw new IllegalArgumentException("Keep the note under " + MAX_NOTE + " characters");
        }
        if (t.tags().size() > MAX_TAGS) {
            throw new IllegalArgumentException("Use at most " + MAX_TAGS + " tags");
        }
        for (String tag : t.tags()) {
            if (tag.length() > MAX_TAG_NAME) {
                throw new IllegalArgumentException("Keep each tag under " + MAX_TAG_NAME + " characters");
            }
        }

        Account to = null;
        long toAmount = 0;
        Category category = null;
        if (t.type() == Transaction.Type.TRANSFER) {
            if (t.toAccount() == null || t.toAccount().id() == 0) {
                throw new IllegalArgumentException("Choose the account the money goes to");
            }
            if (t.toAccount().id() == t.account().id()) {
                throw new IllegalArgumentException("A transfer goes to another account");
            }
            to = t.toAccount();
            if (to.currency().equals(t.account().currency())) {
                // One currency at both ends: what leaves is what arrives.
                toAmount = t.toAmountCents() > 0 ? t.toAmountCents() : t.amountCents();
            } else {
                // Between currencies, what arrived is what the bank says it
                // was; it is never guessed from a rate.
                if (t.toAmountCents() <= 0) {
                    throw new IllegalArgumentException("Enter the amount that arrived in " + to.name()
                            + ", in " + to.currency());
                }
                if (t.toAmountCents() > largest(currency(to.currency()))) {
                    throw new IllegalArgumentException("That amount is too large");
                }
                toAmount = t.toAmountCents();
            }
        } else {
            if (t.category() == null || t.category().id() == 0) {
                throw new IllegalArgumentException("Choose a category");
            }
            Category.Kind wanted = t.type() == Transaction.Type.INCOME ? Category.Kind.INCOME : Category.Kind.EXPENSE;
            if (t.category().kind() != wanted) {
                throw new IllegalArgumentException("\"" + t.category().name() + "\" is a category for "
                        + (wanted == Category.Kind.INCOME ? "expenses" : "income") + "; choose one for "
                        + (wanted == Category.Kind.INCOME ? "income" : "expenses"));
            }
            category = t.category();
        }
        return new Transaction(t.id(), t.type(), t.account(), t.amountCents(), to, toAmount, category,
                merchant, description, t.date(), note, t.tags(), conversion(t, currency), original(t));
    }

    /**
     * The price in another currency, when there is one. In the account's own
     * currency it is the amount itself, so it is not kept twice. A transfer
     * already records both of its ends.
     */
    private Transaction.Original original(Transaction t) throws SQLException {
        Transaction.Original original = t.original();
        if (original == null || original.currency() == null
                || original.currency().equals(t.account().currency())) {
            return null;
        }
        if (t.type() == Transaction.Type.TRANSFER) {
            throw new IllegalArgumentException("A transfer records what left and what arrived; "
                    + "it has no price in another currency");
        }
        CurrencyUnit priced = currency(original.currency());
        if (original.amountCents() <= 0) {
            throw new IllegalArgumentException("The price must be more than zero");
        }
        if (original.amountCents() > largest(priced)) {
            throw new IllegalArgumentException("That price is too large");
        }
        return new Transaction.Original(priced.code(), original.amountCents());
    }

    /**
     * What a transaction is in the base currency. In the base currency, the
     * amount itself; otherwise at the rate given with it (typed by the user),
     * or else the rate in effect on its day.
     *
     * <p>Always from what the account was charged, even when the original
     * price was in the base currency: one rule, so the rate and the base
     * amount stored beside it always agree.
     */
    private Transaction.Conversion conversion(Transaction t, CurrencyUnit currency) throws SQLException {
        CurrencyUnit base = baseCurrency();
        if (currency.code().equals(base.code())) {
            return new Transaction.Conversion(BigDecimal.ONE, t.amountCents());
        }
        BigDecimal rate;
        if (t.conversion() != null && t.conversion().rate() != null) {
            rate = t.conversion().rate();
            if (rate.signum() <= 0) {
                throw new IllegalArgumentException("The rate must be more than zero");
            }
        } else {
            rate = currencies.rateOn(currency.code(), t.date()).map(ExchangeRate::rate).orElseThrow(() ->
                    new IllegalArgumentException("There is no rate for " + currency.code() + " on or before "
                            + t.date() + ". Add one under Currencies, or type the rate."));
        }
        return new Transaction.Conversion(rate,
                Money.convert(t.amountCents(), currency.digits(), rate, base.digits()));
    }

    /** The largest amount accepted in a currency, in its minor units. */
    private static long largest(CurrencyUnit currency) {
        return BigDecimal.valueOf(Money.MAX_UNITS).movePointRight(currency.digits()).longValueExact();
    }

    // --- accounts -------------------------------------------------------------

    /** Every account, in the order they were created. */
    public List<Account> allAccounts() throws SQLException {
        return accounts.findAll();
    }

    /** The account new expenses go to when none is chosen: the first one. */
    public Account defaultAccount() throws SQLException {
        return accounts.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("There is no account"));
    }

    public Account accountNamed(String name) throws SQLException {
        return accounts.findByName(name.strip()).orElseThrow(() ->
                new IllegalArgumentException("There is no account called \"" + name.strip() + "\""));
    }

    /** Every account's balance in cents, by id. */
    public Map<Long, Long> balances() throws SQLException {
        return accounts.balances();
    }

    /** Saves a new account, or changes an existing one (a non-zero id). */
    public Account save(Account account) throws SQLException {
        String name = account.name() == null ? "" : account.name().strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Name the account");
        }
        if (name.length() > MAX_ACCOUNT_NAME) {
            throw new IllegalArgumentException("Keep the name under " + MAX_ACCOUNT_NAME + " characters");
        }
        if (account.kind() == null) {
            throw new IllegalArgumentException("Choose what kind of account it is");
        }
        String code = account.currency().isBlank() ? baseCurrency().code() : account.currency();
        CurrencyUnit currency = currency(code);
        if (Math.abs(account.openingCents()) > largest(currency)) {
            throw new IllegalArgumentException("That opening balance is too large");
        }
        if (account.id() != 0) {
            // Its transactions were recorded in its currency; relabelling
            // them would change every amount's meaning.
            Account stored = accounts.findAll().stream().filter(a -> a.id() == account.id()).findFirst().orElse(null);
            if (stored != null && !stored.currency().equals(code)
                    && (accounts.usage(account.id()) > 0 || recurring.usingAccount(account.id()) > 0)) {
                throw new IllegalArgumentException("\"" + stored.name() + "\" has transactions, so it stays in "
                        + stored.currency());
            }
        }
        var existing = accounts.findByName(name);
        if (existing.isPresent() && existing.get().id() != account.id()) {
            throw new IllegalArgumentException("There is already an account called \"" + name + "\"");
        }
        Account valid = new Account(account.id(), name, account.kind(), code, account.openingCents());
        if (valid.id() == 0) {
            return accounts.insert(valid);
        }
        accounts.update(valid);
        return valid;
    }

    /**
     * Removes an account nothing uses. One with transactions is refused
     * rather than taking them with it, and the last one is kept: every
     * transaction needs an account.
     */
    public void deleteAccount(Account account) throws SQLException {
        int used = accounts.usage(account.id());
        if (used > 0) {
            throw new IllegalArgumentException("\"" + account.name() + "\" has " + used
                    + (used == 1 ? " transaction" : " transactions") + ". Move or delete them first.");
        }
        int rules = recurring.usingAccount(account.id());
        if (rules > 0) {
            throw new IllegalArgumentException("\"" + account.name() + "\" is used by " + rules
                    + (rules == 1 ? " recurring transaction" : " recurring transactions") + ". Change or delete "
                    + (rules == 1 ? "it" : "them") + " first.");
        }
        if (accounts.findAll().size() <= 1) {
            throw new IllegalArgumentException("Keep at least one account");
        }
        accounts.delete(account.id());
    }

    /** How many transactions start or end in an account. */
    public int usage(Account account) throws SQLException {
        return accounts.usage(account.id());
    }

    // --- categories -----------------------------------------------------------

    public List<Category> allCategories() throws SQLException {
        return categories.findAll();
    }

    /** The categories of one kind, alphabetically. */
    public List<Category> categories(Category.Kind kind) throws SQLException {
        return categories.findAll().stream().filter(c -> c.kind() == kind).toList();
    }

    public Category categoryNamed(String name) throws SQLException {
        return categories.findByName(name.strip()).orElseThrow(() ->
                new IllegalArgumentException("There is no category called \"" + name.strip() + "\""));
    }

    /** Saves a new category, or changes an existing one (a non-zero id). */
    public Category save(Category category) throws SQLException {
        String name = category.name() == null ? "" : category.name().strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Name the category");
        }
        if (name.length() > MAX_CATEGORY_NAME) {
            throw new IllegalArgumentException("Keep the name under " + MAX_CATEGORY_NAME + " characters");
        }
        if (category.color() == null || !COLOR.matcher(category.color()).matches()) {
            throw new IllegalArgumentException("Choose a colour");
        }
        var existing = categories.findByName(name);
        if (existing.isPresent() && existing.get().id() != category.id()) {
            throw new IllegalArgumentException("There is already a category called \"" + name + "\"");
        }
        if (category.id() != 0) {
            // What it is now, looked up by id: a rename changes the name.
            Category stored = categories.findAll().stream().filter(c -> c.id() == category.id())
                    .findFirst().orElse(null);
            if (stored != null && stored.kind() != category.kind() && (categories.usage(category.id()) > 0
                    || recurring.usingCategory(category.id()) > 0 || budgets.usingCategory(category.id()) > 0)) {
                throw new IllegalArgumentException("\"" + stored.name() + "\" is in use, so it stays a category for "
                        + (stored.kind() == Category.Kind.INCOME ? "income" : "expenses"));
            }
        }
        Category valid = new Category(category.id(), name, category.color().toLowerCase(), category.kind());
        if (valid.id() == 0) {
            return categories.insert(valid);
        }
        categories.update(valid);
        return valid;
    }

    /**
     * Removes a category nobody uses. One still holding transactions is
     * refused rather than taking them with it: deleting a label should never
     * delete money the user recorded.
     */
    public void deleteCategory(Category category) throws SQLException {
        int used = categories.usage(category.id());
        if (used > 0) {
            throw new IllegalArgumentException("\"" + category.name() + "\" is used by " + used
                    + (used == 1 ? " transaction" : " transactions") + ". Move them to another category first.");
        }
        int rules = recurring.usingCategory(category.id());
        int limits = budgets.usingCategory(category.id());
        if (rules + limits > 0) {
            throw new IllegalArgumentException("\"" + category.name() + "\" is used by "
                    + (rules > 0 ? rules + (rules == 1 ? " recurring transaction" : " recurring transactions") : "")
                    + (rules > 0 && limits > 0 ? " and " : "")
                    + (limits > 0 ? limits + (limits == 1 ? " budget" : " budgets") : "")
                    + ". Change or delete " + (rules + limits == 1 ? "it" : "them") + " first.");
        }
        categories.delete(category.id());
    }

    /** How many transactions a category holds. */
    public int usage(Category category) throws SQLException {
        return categories.usage(category.id());
    }

    // --- currencies -----------------------------------------------------------

    /** The currency every total is in. */
    public CurrencyUnit baseCurrency() throws SQLException {
        return currency(currencies.baseCode());
    }

    /**
     * A currency by code: one the user defined, or an ISO 4217 one.
     *
     * @throws IllegalArgumentException when there is no such currency
     */
    public CurrencyUnit currency(String code) throws SQLException {
        String wanted = code == null ? "" : code.strip().toUpperCase(java.util.Locale.ROOT);
        for (CurrencyUnit custom : currencies.customCurrencies()) {
            if (custom.code().equals(wanted)) {
                return custom;
            }
        }
        return CurrencyUnit.iso(wanted).orElseThrow(() ->
                new IllegalArgumentException("There is no currency " + wanted));
    }

    /** Every currency that can be chosen: ISO 4217's, then the user's own, by code. */
    public List<CurrencyUnit> allCurrencies() throws SQLException {
        TreeSet<String> codes = new TreeSet<>();
        for (Currency iso : Currency.getAvailableCurrencies()) {
            codes.add(iso.getCurrencyCode());
        }
        List<CurrencyUnit> all = new ArrayList<>();
        for (String code : codes) {
            CurrencyUnit.iso(code).ifPresent(all::add);
        }
        all.addAll(currencies.customCurrencies());
        all.sort(Comparator.comparing(CurrencyUnit::code));
        return all;
    }

    public List<CurrencyUnit> customCurrencies() throws SQLException {
        return currencies.customCurrencies();
    }

    /**
     * Makes another currency the base. Only while nothing depends on the old
     * one: every account in it, and no rates, which are all worth something
     * in the old base. Then it is a new name, and nothing is converted.
     */
    public void changeBaseCurrency(String code) throws SQLException {
        CurrencyUnit next = currency(code);
        CurrencyUnit base = baseCurrency();
        if (next.code().equals(base.code())) {
            return;
        }
        int inBase = currencies.accountsIn(base.code());
        if (inBase != accounts.findAll().size()) {
            throw new IllegalArgumentException("Some accounts are in other currencies, so the base currency stays "
                    + base.code());
        }
        // Rates the user entered are in the old base and would silently mean
        // something else. Fetched ones can be fetched again in the new base,
        // and nothing recorded depends on them: every account is in the base,
        // so no transaction was converted with one.
        if (currencies.manualRateCount() > 0) {
            throw new IllegalArgumentException("The rates you entered are in " + base.code()
                    + ", so the base currency stays " + base.code() + " while there are any");
        }
        // Amounts are whole minor units: a cent is not a yen. With other
        // decimals, every amount already stored would change its meaning.
        if (next.digits() != base.digits()
                && (transactions.count() > 0 || budgets.count() > 0
                        || accounts.findAll().stream().anyMatch(a -> a.openingCents() != 0)
                        || !recurring.findAll().isEmpty())) {
            throw new IllegalArgumentException(next.code() + " has " + next.digits() + " decimals and " + base.code()
                    + " has " + base.digits() + ", so the amounts already recorded would change");
        }
        currencies.deleteFetched();
        currencies.changeBase(next.code());
    }

    /** Adds a currency of the user's own, or changes one no account uses yet. */
    public CurrencyUnit saveCustomCurrency(CurrencyUnit currency) throws SQLException {
        String code = currency.code() == null ? "" : currency.code().strip().toUpperCase(java.util.Locale.ROOT);
        if (!CUSTOM_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("A code is three to five letters, such as BTC");
        }
        if (CurrencyUnit.iso(code).isPresent()) {
            throw new IllegalArgumentException(code + " is already a currency");
        }
        String name = currency.name() == null ? "" : currency.name().strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Name the currency");
        }
        if (name.length() > MAX_CURRENCY_NAME) {
            throw new IllegalArgumentException("Keep the name under " + MAX_CURRENCY_NAME + " characters");
        }
        if (currency.digits() < 0 || currency.digits() > 4) {
            throw new IllegalArgumentException("A currency has from 0 to 4 decimals");
        }
        Optional<CurrencyUnit> existing = currencies.customCurrencies().stream()
                .filter(c -> c.code().equals(code)).findFirst();
        if (existing.isPresent() && existing.get().digits() != currency.digits()
                && (currencies.accountsIn(code) > 0 || transactions.originalsIn(code) > 0)) {
            throw new IllegalArgumentException(code + " is in use, so it keeps " + existing.get().digits() + " decimals");
        }
        CurrencyUnit valid = new CurrencyUnit(code, name, currency.digits(), true);
        currencies.saveCustom(valid);
        return valid;
    }

    /** Removes a currency of the user's own that no account or price is in. */
    public void deleteCustomCurrency(CurrencyUnit currency) throws SQLException {
        int used = currencies.accountsIn(currency.code());
        if (used > 0) {
            throw new IllegalArgumentException(currency.code() + " is the currency of " + used
                    + (used == 1 ? " account" : " accounts"));
        }
        int priced = transactions.originalsIn(currency.code());
        if (priced > 0) {
            throw new IllegalArgumentException(priced + (priced == 1 ? " transaction has its price" : " transactions have "
                    + "their price") + " in " + currency.code());
        }
        currencies.deleteCustom(currency.code());
    }

    /** Every rate, by currency and then newest first. */
    public List<ExchangeRate> rates() throws SQLException {
        return currencies.rates();
    }

    /** The rate in effect for {@code code} on {@code day}, if there is one. */
    public Optional<ExchangeRate> rateOn(String code, LocalDate day) throws SQLException {
        return currencies.rateOn(code, day);
    }

    /**
     * Adds a rate, or replaces the one for the same currency and day. Rates
     * saved later never change transactions already recorded: each keeps the
     * rate it was saved with.
     */
    public ExchangeRate saveRate(ExchangeRate rate) throws SQLException {
        CurrencyUnit currency = currency(rate.currency());
        if (currency.code().equals(baseCurrency().code())) {
            throw new IllegalArgumentException(currency.code() + " is the base currency: its rate is always 1");
        }
        if (rate.effectiveOn() == null) {
            throw new IllegalArgumentException("Choose the day the rate applies from");
        }
        if (rate.rate() == null || rate.rate().signum() <= 0) {
            throw new IllegalArgumentException("The rate must be more than zero");
        }
        if (rate.rate().stripTrailingZeros().scale() > Money.RATE_SCALE) {
            throw new IllegalArgumentException("Use at most " + Money.RATE_SCALE + " decimals in a rate");
        }
        ExchangeRate valid = new ExchangeRate(currency.code(), rate.effectiveOn(), rate.rate().stripTrailingZeros());
        currencies.saveRate(valid);
        return valid;
    }

    /**
     * The currencies rates are wanted for: the accounts', the ones prices were
     * paid in, and any that already has a rate. Not the base.
     */
    public List<String> currenciesInUse() throws SQLException {
        TreeSet<String> codes = new TreeSet<>(currencies.accountCurrencies());
        codes.addAll(currencies.priceCurrencies());
        currencies.rates().forEach(rate -> codes.add(rate.currency()));
        codes.remove(baseCurrency().code());
        return List.copyOf(codes);
    }

    /**
     * Whether the European Central Bank's feed leaves anything in use without
     * a rate: the base itself, or a currency, it does not publish. Only then
     * is the other feed worth downloading.
     */
    public boolean needsMoreThan(EcbRates.Feed ecb) throws SQLException {
        String base = baseCurrency().code();
        return !ecb.perEuro().containsKey(base)
                || currenciesInUse().stream().anyMatch(code -> !ecb.perEuro().containsKey(code));
    }

    /** {@link #keepRates(EcbRates.Feed, EcbRates.Loader)}, with the European Central Bank's feed alone. */
    public EcbRates.Result keepRates(EcbRates.Feed feed) throws SQLException {
        return keepRates(feed, null);
    }

    /**
     * Keeps a day's rates for the currencies in use: the European Central
     * Bank's where it publishes both the currency and the base, and the other
     * feed's for the rest. Each rate comes from one feed; they are never
     * mixed in one sum. The other feed is only downloaded when something is
     * missing.
     *
     * <p>A day that already has a rate keeps it: the user's own, above all,
     * is never replaced by a fetched one. When the other feed cannot be had,
     * the bank's rates are still kept and the result says why the rest are
     * missing.
     */
    public EcbRates.Result keepRates(EcbRates.Feed ecb, EcbRates.Loader other) throws SQLException {
        String base = baseCurrency().code();
        List<String> wanted = currenciesInUse();
        List<ExchangeRate> rates = new ArrayList<>();
        List<String> rest = new ArrayList<>(wanted);
        if (ecb.perEuro().containsKey(base)) {
            rates.addAll(EcbRates.ratesIn(ecb, base, wanted, ExchangeRate.Source.ECB));
            rates.forEach(rate -> rest.remove(rate.currency()));
        }
        String problem = null;
        LocalDate day = ecb.day();
        if (!rest.isEmpty() && other != null) {
            try {
                EcbRates.Feed more = other.load();
                if (more.perEuro().containsKey(base)) {
                    List<ExchangeRate> found =
                            EcbRates.ratesIn(more, base, rest, ExchangeRate.Source.EXCHANGE_RATE_API);
                    rates.addAll(found);
                    found.forEach(rate -> rest.remove(rate.currency()));
                    if (!ecb.perEuro().containsKey(base)) {
                        day = more.day();
                    }
                }
            } catch (java.io.IOException | IllegalArgumentException e) {
                problem = e.getMessage();
            }
        }
        int added = currencies.addFetched(rates);
        return new EcbRates.Result(day, added, List.copyOf(rest), problem);
    }

    public void deleteRate(ExchangeRate rate) throws SQLException {
        currencies.deleteRate(rate);
    }

    /**
     * Converts an amount between any two currencies, through the base, at the
     * rates in effect on {@code day}.
     *
     * @throws IllegalArgumentException when either currency has no rate then
     */
    public long convert(long minor, String from, String to, LocalDate day) throws SQLException {
        CurrencyUnit source = currency(from);
        CurrencyUnit target = currency(to);
        BigDecimal value = BigDecimal.valueOf(minor, source.digits()).multiply(rateToBase(source, day))
                .divide(rateToBase(target, day), MathContext.DECIMAL128);
        return value.setScale(target.digits(), RoundingMode.HALF_EVEN).unscaledValue().longValueExact();
    }

    private BigDecimal rateToBase(CurrencyUnit currency, LocalDate day) throws SQLException {
        if (currency.code().equals(baseCurrency().code())) {
            return BigDecimal.ONE;
        }
        return currencies.rateOn(currency.code(), day).map(ExchangeRate::rate).orElseThrow(() ->
                new IllegalArgumentException("There is no rate for " + currency.code() + " on or before " + day));
    }

    /**
     * What is held, what is owed, and the difference, in the base currency,
     * at today's rates. An account in a currency with no rate yet cannot be
     * counted; its currency is named instead of guessed.
     *
     * @param haveCents   the balances above zero, added up
     * @param oweCents    the balances below zero, as a positive amount
     * @param uncounted   currencies left out for want of a rate
     */
    public record NetWorth(long haveCents, long oweCents, List<String> uncounted) {
        public long netCents() {
            return haveCents - oweCents;
        }
    }

    public NetWorth netWorth(LocalDate day) throws SQLException {
        Map<Long, Long> balances = accounts.balances();
        CurrencyUnit base = baseCurrency();
        long have = 0;
        long owe = 0;
        TreeSet<String> uncounted = new TreeSet<>();
        for (Account account : accounts.findAll()) {
            long balance = balances.getOrDefault(account.id(), 0L);
            long inBase;
            if (balance == 0) {
                // Nothing in any currency: no rate is needed to know that.
                continue;
            }
            if (account.currency().equals(base.code())) {
                inBase = balance;
            } else {
                Optional<ExchangeRate> rate = currencies.rateOn(account.currency(), day);
                if (rate.isEmpty()) {
                    uncounted.add(account.currency());
                    continue;
                }
                inBase = Money.convert(balance, currency(account.currency()).digits(), rate.get().rate(), base.digits());
            }
            if (inBase >= 0) {
                have += inBase;
            } else {
                owe -= inBase;
            }
        }
        return new NetWorth(have, owe, List.copyOf(uncounted));
    }

    // --- budgets ---------------------------------------------------------------

    /** Every budget: the overall one first, then by category. */
    public List<Budget> allBudgets() throws SQLException {
        return budgets.findAll();
    }

    /** Saves a new budget, or changes one (a non-zero id). */
    public Budget save(Budget budget) throws SQLException {
        if (budget.period() == null) {
            throw new IllegalArgumentException("Choose how long a budget period is");
        }
        if (budget.amountCents() <= 0) {
            throw new IllegalArgumentException("The limit must be more than zero");
        }
        if (budget.amountCents() > largest(baseCurrency())) {
            throw new IllegalArgumentException("That limit is too large");
        }
        if (budget.category() != null && budget.category().kind() != Category.Kind.EXPENSE) {
            throw new IllegalArgumentException("A budget limits spending: choose a category for expenses");
        }
        if (budget.period() == Budget.Period.CUSTOM) {
            if (budget.startsOn() == null || budget.endsOn() == null) {
                throw new IllegalArgumentException("Choose the first and the last day");
            }
            if (budget.endsOn().isBefore(budget.startsOn())) {
                throw new IllegalArgumentException("The last day comes before the first");
            }
        }
        long categoryId = budget.category() == null ? 0 : budget.category().id();
        boolean taken = budgets.findAll().stream().anyMatch(other -> other.id() != budget.id()
                && other.period() == budget.period() && budget.period() != Budget.Period.CUSTOM
                && (other.category() == null ? 0 : other.category().id()) == categoryId);
        if (taken) {
            throw new IllegalArgumentException("There is already a " + budget.period().label().toLowerCase(java.util.Locale.ROOT)
                    + " budget for " + (budget.category() == null ? "all spending" : budget.category().name()));
        }
        Budget valid = budget.period() == Budget.Period.CUSTOM ? budget
                : new Budget(budget.id(), budget.category(), budget.period(), budget.amountCents(), null, null);
        if (valid.id() == 0) {
            return budgets.insert(valid);
        }
        budgets.update(valid);
        return valid;
    }

    public void deleteBudget(Budget budget) throws SQLException {
        budgets.delete(budget.id());
    }

    /**
     * Where a budget stands in the period {@code today} falls in.
     *
     * @param budget    the budget
     * @param from      the period's first day
     * @param to        the period's last day
     * @param spentCents what was spent so far, in the base currency
     * @param leftPerDayCents what can still be spent each remaining day,
     *                  today included; 0 when nothing is left or the period
     *                  is over
     * @param projectedCents what the period ends at if spending goes on as it
     *                  has: the pace so far, carried to the last day
     */
    public record BudgetProgress(Budget budget, LocalDate from, LocalDate to, long spentCents, long leftPerDayCents,
            long projectedCents) {

        /** How the budget stands, for its colour. */
        public enum State { FINE, CLOSE, OVER }

        /** Past the limit; at 80% or heading past it; otherwise fine. */
        public State state() {
            if (spentCents > budget.amountCents()) {
                return State.OVER;
            }
            return spentCents * 10 >= budget.amountCents() * 8 || projectedCents > budget.amountCents()
                    ? State.CLOSE : State.FINE;
        }

        /** How much of the limit is spent, from 0 up; past 1 when over. */
        public double fraction() {
            return (double) spentCents / budget.amountCents();
        }
    }

    /**
     * Every budget's standing on {@code today}. Weeks start on the day the
     * user chose in Settings, which the caller passes in: it is a setting,
     * not part of the data.
     */
    public List<BudgetProgress> budgetProgress(LocalDate today, java.time.DayOfWeek weekStart) throws SQLException {
        List<BudgetProgress> all = new ArrayList<>();
        for (Budget budget : budgets.findAll()) {
            LocalDate[] period = budget.periodOf(today, weekStart);
            long categoryId = budget.category() == null ? 0 : budget.category().id();
            long spent = transactions.spent(categoryId, period[0], period[1]);
            long days = java.time.temporal.ChronoUnit.DAYS.between(period[0], period[1]) + 1;
            long elapsed = today.isBefore(period[0]) ? 0
                    : java.time.temporal.ChronoUnit.DAYS.between(period[0], today.isAfter(period[1]) ? period[1] : today) + 1;
            long remaining = today.isAfter(period[1]) ? 0 : days - Math.max(0, elapsed - 1);
            long left = Math.max(0, budget.amountCents() - spent);
            long perDay = remaining == 0 ? 0 : left / remaining;
            long projected = elapsed == 0 ? spent : BigDecimal.valueOf(spent).multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(elapsed), 0, RoundingMode.HALF_EVEN).longValueExact();
            all.add(new BudgetProgress(budget, period[0], period[1], spent, perDay, projected));
        }
        return all;
    }

    // --- recurring --------------------------------------------------------------

    /** The most occurrences a rule records in one go, catching up. */
    public static final int CATCH_UP_LIMIT = 100;

    /** Every rule, by description. */
    public List<Recurring> allRecurring() throws SQLException {
        return recurring.findAll();
    }

    /**
     * Saves a new rule, or changes one. What it would record is checked as a
     * transaction is, on its first day; a transfer between currencies needs
     * the amount that arrives, since a rate would only guess it.
     */
    public Recurring save(Recurring rule) throws SQLException {
        if (rule.frequency() == null) {
            throw new IllegalArgumentException("Choose how often it repeats");
        }
        if (rule.every() < 1 || rule.every() > 366) {
            throw new IllegalArgumentException("It repeats every 1 to 366 " + rule.frequency().key() + "s");
        }
        if (rule.startsOn() == null) {
            throw new IllegalArgumentException("Choose the first day");
        }
        if (rule.endsOn() != null && rule.endsOn().isBefore(rule.startsOn())) {
            throw new IllegalArgumentException("It ends before it starts");
        }
        Transaction checked = checkShape(rule.toTransaction(rule.startsOn()));
        Recurring valid = new Recurring(rule.id(), checked.type(), checked.account(), checked.amountCents(),
                checked.toAccount(), checked.toAmountCents(), checked.category(), checked.merchant(),
                checked.description(), checked.note(), rule.frequency(), rule.every(), rule.startsOn(), rule.endsOn(),
                rule.done(), rule.bill(), rule.askFirst(), rule.paused());
        if (valid.id() == 0) {
            return recurring.insert(valid);
        }
        recurring.update(valid);
        return valid;
    }

    /**
     * Checks a transaction as {@link #save(Transaction)} does, except for its
     * rate: a rule's occurrences are converted on their own days.
     */
    private Transaction checkShape(Transaction t) throws SQLException {
        String base = baseCurrency().code();
        Transaction asIfBase = t;
        if (t.account() != null && !t.account().currency().equals(base)) {
            asIfBase = new Transaction(t.id(), t.type(), t.account(), t.amountCents(), t.toAccount(),
                    t.toAmountCents(), t.category(), t.merchant(), t.description(), t.date(), t.note(), t.tags(),
                    new Transaction.Conversion(BigDecimal.ONE, 0), null);
        }
        return validate(asIfBase);
    }

    /** Removes a rule. What it recorded stays, as ordinary transactions. */
    public void deleteRecurring(Recurring rule) throws SQLException {
        recurring.delete(rule.id());
    }

    /** How many occurrences of a rule fall on or before {@code today} and are not dealt with yet. */
    public int overdue(Recurring rule, LocalDate today) {
        int count = 0;
        for (int n = rule.done(); count <= CATCH_UP_LIMIT; n++) {
            LocalDate day = rule.occurrence(n);
            if (day.isAfter(today) || (rule.endsOn() != null && day.isAfter(rule.endsOn()))) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * An occurrence waiting for the user: one that asks first, or one that
     * could not be recorded by itself.
     *
     * @param rule   its rule, as it stands
     * @param day    its date
     * @param reason why it waits: null when the rule asks first
     */
    public record Due(Recurring rule, LocalDate day, String reason) {
    }

    /**
     * What recording the due occurrences did.
     *
     * @param recorded how many were recorded
     * @param waiting  the ones left for the user
     */
    public record Catch(int recorded, List<Due> waiting) {
    }

    /**
     * Records every occurrence that has fallen due by {@code today}, for rules
     * that do not ask first and are not paused, at most
     * {@link #CATCH_UP_LIMIT} per rule. Each is recorded and counted on its
     * rule in one change, so none is ever recorded twice. One that cannot be
     * recorded (in another currency, with no rate for its day) stops its rule
     * there and waits for the user.
     */
    public Catch recordDue(LocalDate today) throws SQLException {
        int recorded = 0;
        List<Due> waiting = new ArrayList<>();
        for (Recurring rule : recurring.findAll()) {
            if (rule.paused()) {
                continue;
            }
            Recurring current = rule;
            for (int n = 0; n < CATCH_UP_LIMIT; n++) {
                LocalDate day = current.nextDue();
                if (day == null || day.isAfter(today)) {
                    break;
                }
                if (current.askFirst()) {
                    waiting.add(new Due(current, day, null));
                    break;
                }
                Transaction valid;
                try {
                    valid = validate(current.toTransaction(day));
                } catch (IllegalArgumentException e) {
                    waiting.add(new Due(current, day, e.getMessage()));
                    break;
                }
                if (recurring.recordOccurrence(current, valid) == null) {
                    break;
                }
                recorded++;
                current = current.withDone(current.done() + 1);
            }
        }
        return new Catch(recorded, waiting);
    }

    /**
     * Records one waiting occurrence, as the user asked. {@code rate}, when
     * given, is the rate the user typed for an account in another currency.
     */
    public Transaction record(Due due, BigDecimal rate) throws SQLException {
        Transaction occurrence = due.rule().toTransaction(due.day());
        if (rate != null) {
            occurrence = new Transaction(0, occurrence.type(), occurrence.account(), occurrence.amountCents(),
                    occurrence.toAccount(), occurrence.toAmountCents(), occurrence.category(), occurrence.merchant(),
                    occurrence.description(), occurrence.date(), occurrence.note(), occurrence.tags(),
                    new Transaction.Conversion(rate, 0), null);
        }
        Transaction saved = recurring.recordOccurrence(due.rule(), validate(occurrence));
        if (saved == null) {
            throw new IllegalArgumentException("This occurrence was dealt with already");
        }
        return saved;
    }

    /**
     * The occurrences waiting for the user on {@code today}, without recording
     * anything: the next one of each rule that asks first, and of each that
     * could not be recorded by itself, with the reason.
     */
    public List<Due> waiting(LocalDate today) throws SQLException {
        List<Due> waiting = new ArrayList<>();
        for (Recurring rule : recurring.findAll()) {
            LocalDate day = rule.nextDue();
            if (rule.paused() || day == null || day.isAfter(today)) {
                continue;
            }
            if (rule.askFirst()) {
                waiting.add(new Due(rule, day, null));
                continue;
            }
            try {
                validate(rule.toTransaction(day));
            } catch (IllegalArgumentException e) {
                waiting.add(new Due(rule, day, e.getMessage()));
            }
        }
        waiting.sort(Comparator.comparing(Due::day));
        return waiting;
    }

    /** Lets one waiting occurrence go, recording nothing. */
    public void skip(Due due) throws SQLException {
        if (!recurring.skipOccurrence(due.rule())) {
            throw new IllegalArgumentException("This occurrence was dealt with already");
        }
    }

    /**
     * The bills due in the next {@code days} days, today included, soonest
     * first, with each one's date: every occurrence, so a weekly bill shows
     * each week.
     */
    public List<Due> upcomingBills(LocalDate today, int days) throws SQLException {
        LocalDate until = today.plusDays(days - 1L);
        List<Due> bills = new ArrayList<>();
        for (Recurring rule : recurring.findAll()) {
            if (!rule.bill() || rule.paused()) {
                continue;
            }
            for (int n = rule.done(); n < rule.done() + 400; n++) {
                LocalDate day = rule.occurrence(n);
                if (day.isAfter(until) || (rule.endsOn() != null && day.isAfter(rule.endsOn()))) {
                    break;
                }
                if (!day.isBefore(today)) {
                    bills.add(new Due(rule, day, null));
                }
            }
        }
        bills.sort(Comparator.comparing(Due::day).thenComparing(due -> due.rule().description()));
        return bills;
    }

    /** How many transactions a rule has recorded. */
    public int recordedBy(Recurring rule) throws SQLException {
        return transactions.recordedBy(rule.id());
    }

    // --- rates in use --------------------------------------------------------------

    /**
     * The rate in effect today for each currency in use, when there is one;
     * a currency with none is listed with none, so the gap shows.
     */
    public java.util.Map<String, Optional<ExchangeRate>> ratesInUse(LocalDate today) throws SQLException {
        java.util.Map<String, Optional<ExchangeRate>> inUse = new java.util.TreeMap<>();
        for (String code : currenciesInUse()) {
            inUse.put(code, currencies.rateOn(code, today));
        }
        return inUse;
    }

    /**
     * A rate to suggest for {@code code} on {@code day}, when the user has
     * none to hand: the latest known on or before it, else the nearest after
     * it. Only a suggestion; nothing is saved.
     */
    public Optional<ExchangeRate> suggestRate(String code, LocalDate day) throws SQLException {
        Optional<ExchangeRate> before = currencies.rateOn(code, day);
        if (before.isPresent()) {
            return before;
        }
        return currencies.rates().stream().filter(rate -> rate.currency().equals(code))
                .min(Comparator.comparing(ExchangeRate::effectiveOn));
    }
}
