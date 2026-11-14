package com.geetansh.expensetracker.service;

import com.geetansh.expensetracker.model.Summary;
import com.geetansh.expensetracker.model.Transaction;
import com.geetansh.expensetracker.model.TransactionType;
import com.geetansh.expensetracker.repository.BudgetStore;
import com.geetansh.expensetracker.repository.TransactionRepository;
import com.geetansh.expensetracker.util.Csv;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business rules: validation, filtering by month, totals and CSV export. */
public class TransactionService {
    public static final List<String> DEFAULT_CATEGORIES = List.of(
            "Food", "Transport", "Rent", "Shopping", "Bills",
            "Health", "Education", "Entertainment", "Salary", "Other");

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");

    private final TransactionRepository repository;
    private final BudgetStore budgetStore;

    public TransactionService(TransactionRepository repository, BudgetStore budgetStore) {
        this.repository = repository;
        this.budgetStore = budgetStore;
    }

    /** @param month a month to filter by, or null for everything */
    public List<Transaction> list(YearMonth month) {
        return repository.findAll().stream()
                .filter(t -> month == null || YearMonth.from(t.date()).equals(month))
                .sorted(Comparator.comparing(Transaction::date).reversed()
                        .thenComparing(Comparator.comparingLong(Transaction::id).reversed()))
                .toList();
    }

    public Transaction get(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Transaction " + id + " not found"));
    }

    public Transaction create(Transaction input) {
        return repository.save(validate(input).withId(0));
    }

    public Transaction update(long id, Transaction input) {
        get(id);
        return repository.save(validate(input).withId(id));
    }

    public void delete(long id) {
        if (!repository.deleteById(id)) {
            throw new NotFoundException("Transaction " + id + " not found");
        }
    }

    public Summary summary(YearMonth month) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        List<Transaction> rows = list(month);
        for (Transaction t : rows) {
            if (t.type() == TransactionType.INCOME) {
                income = income.add(t.amount());
            } else {
                expense = expense.add(t.amount());
                byCategory.merge(t.category(), t.amount(), BigDecimal::add);
            }
        }
        Map<String, BigDecimal> sorted = new LinkedHashMap<>();
        byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return new Summary(month == null ? "all" : month.toString(), income, expense,
                income.subtract(expense), budgetStore.get(), rows.size(), sorted);
    }

    public BigDecimal getBudget() {
        return budgetStore.get();
    }

    public BigDecimal setBudget(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_AMOUNT) > 0) {
            throw new ValidationException("Budget must be between 0 and 1,000,000,000.");
        }
        BigDecimal v = value.setScale(2, RoundingMode.HALF_UP);
        budgetStore.set(v);
        return v;
    }

    public String exportCsv(YearMonth month) {
        StringBuilder sb = new StringBuilder("Date,Type,Title,Category,Amount,Note\r\n");
        for (Transaction t : list(month)) {
            sb.append(Csv.join(List.of(
                    t.date().toString(), t.type().name(),
                    Csv.safeForSpreadsheet(t.title()), Csv.safeForSpreadsheet(t.category()),
                    t.amount().toPlainString(), Csv.safeForSpreadsheet(t.note() == null ? "" : t.note()))))
              .append("\r\n");
        }
        return sb.toString();
    }

    /** Checks and cleans every field; returns a normalised copy. */
    Transaction validate(Transaction in) {
        if (in == null) throw new ValidationException("Transaction is required.");
        if (in.type() == null) throw new ValidationException("Type must be INCOME or EXPENSE.");

        String title = clean(in.title());
        if (title.isEmpty()) throw new ValidationException("Title is required.");
        if (title.length() > 80) throw new ValidationException("Title must be 80 characters or fewer.");

        String category = clean(in.category());
        if (category.isEmpty()) throw new ValidationException("Category is required.");
        if (category.length() > 30) throw new ValidationException("Category must be 30 characters or fewer.");

        BigDecimal amount = in.amount();
        if (amount == null || amount.signum() <= 0) throw new ValidationException("Amount must be greater than 0.");
        if (amount.compareTo(MAX_AMOUNT) > 0) throw new ValidationException("Amount is too large.");
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() == 0) throw new ValidationException("Amount must be at least 0.01.");

        LocalDate date = in.date();
        if (date == null) throw new ValidationException("Date is required.");
        if (date.getYear() < 2000 || date.getYear() > 2100) throw new ValidationException("Date must be between 2000 and 2100.");

        String note = clean(in.note());
        if (note.length() > 200) throw new ValidationException("Note must be 200 characters or fewer.");

        return new Transaction(in.id(), in.type(), title, category, amount, date, note);
    }

    /** Trims, collapses whitespace and strips control characters (including newlines). */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
    }
}
