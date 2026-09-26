package com.example.expensetracker.service;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.CategoryTotal;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.AccountRepository;
import com.example.expensetracker.repository.CategoryRepository;
import com.example.expensetracker.repository.Database;
import com.example.expensetracker.repository.TransactionRepository;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
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

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final CategoryRepository categories;

    public LedgerService(Database database) {
        this.transactions = new TransactionRepository(database);
        this.accounts = new AccountRepository(database);
        this.categories = new CategoryRepository(database);
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

    /** A copy of {@code transaction}, dated today, saved as a new one. */
    public Transaction duplicate(Transaction transaction) throws SQLException {
        return save(transaction.duplicate(LocalDate.now()));
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
        if (t.amountCents() <= 0 || t.amountCents() > Money.MAX_CENTS) {
            throw new IllegalArgumentException("The amount must be more than zero");
        }
        if (t.account() == null || t.account().id() == 0) {
            throw new IllegalArgumentException("Choose an account");
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
            // One currency for every account until currencies can be chosen:
            // what leaves is what arrives.
            toAmount = t.toAmountCents() > 0 ? t.toAmountCents() : t.amountCents();
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
                merchant, description, t.date(), note, t.tags());
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
        if (Math.abs(account.openingCents()) > Money.MAX_CENTS) {
            throw new IllegalArgumentException("That opening balance is too large");
        }
        var existing = accounts.findByName(name);
        if (existing.isPresent() && existing.get().id() != account.id()) {
            throw new IllegalArgumentException("There is already an account called \"" + name + "\"");
        }
        Account valid = new Account(account.id(), name, account.kind(), account.currency(), account.openingCents());
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
            if (stored != null && stored.kind() != category.kind() && categories.usage(category.id()) > 0) {
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
        categories.delete(category.id());
    }

    /** How many transactions a category holds. */
    public int usage(Category category) throws SQLException {
        return categories.usage(category.id());
    }
}
