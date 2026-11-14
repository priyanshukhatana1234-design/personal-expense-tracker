package com.geetansh.expensetracker;

import com.geetansh.expensetracker.model.Transaction;
import com.geetansh.expensetracker.model.TransactionType;
import com.geetansh.expensetracker.repository.BudgetStore;
import com.geetansh.expensetracker.repository.CsvTransactionRepository;
import com.geetansh.expensetracker.repository.TransactionRepository;
import com.geetansh.expensetracker.service.TransactionService;
import com.geetansh.expensetracker.web.ApiServer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Entry point.
 *   java -cp out com.geetansh.expensetracker.Main [--port 8080] [--data data] [--demo]
 */
public class Main {
    public static void main(String[] args) throws Exception {
        int port = 8080;
        Path dataDir = Path.of("data");
        boolean demo = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--data" -> dataDir = Path.of(args[++i]);
                case "--demo" -> demo = true;
                default -> {
                    System.err.println("Unknown option: " + args[i]);
                    System.err.println("Usage: Main [--port 8080] [--data data] [--demo]");
                    System.exit(2);
                }
            }
        }

        TransactionRepository repository = new CsvTransactionRepository(dataDir.resolve("expenses.csv"));
        BudgetStore budget = new BudgetStore(dataDir.resolve("settings.properties"));
        TransactionService service = new TransactionService(repository, budget);

        if (demo && service.list(null).isEmpty()) seedDemoData(service);

        ApiServer server = new ApiServer(service);
        server.start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.println("Personal Expense Tracker running at http://localhost:" + server.port());
        System.out.println("Data folder: " + dataDir.toAbsolutePath() + "   (Ctrl+C to stop)");
    }

    private static void seedDemoData(TransactionService s) {
        LocalDate t = LocalDate.now();
        s.setBudget(new BigDecimal("15000"));
        add(s, TransactionType.INCOME, "Monthly allowance", "Salary", "20000", t.withDayOfMonth(1), "");
        add(s, TransactionType.EXPENSE, "Hostel rent", "Rent", "6000", t.withDayOfMonth(2), "");
        add(s, TransactionType.EXPENSE, "Mess and groceries", "Food", "3200", t.withDayOfMonth(3), "");
        add(s, TransactionType.EXPENSE, "Metro card top-up", "Transport", "500", t.withDayOfMonth(4), "");
        add(s, TransactionType.EXPENSE, "Data pack", "Bills", "299", t.withDayOfMonth(5), "");
        add(s, TransactionType.EXPENSE, "Java course", "Education", "1499", t.withDayOfMonth(6), "Udemy sale");
        add(s, TransactionType.EXPENSE, "Movie night", "Entertainment", "450", t.withDayOfMonth(7), "");
    }

    private static void add(TransactionService s, TransactionType type, String title, String category,
                            String amount, LocalDate date, String note) {
        s.create(new Transaction(0, type, title, category, new BigDecimal(amount), date, note));
    }
}
