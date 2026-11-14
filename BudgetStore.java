package com.geetansh.expensetracker.repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Stores the single monthly budget in a small properties file. */
public class BudgetStore {
    private static final String KEY = "monthlyBudget";
    private final Path file;
    private BigDecimal budget = BigDecimal.ZERO;

    public BudgetStore(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                Properties p = new Properties();
                p.load(in);
                budget = new BigDecimal(p.getProperty(KEY, "0"));
            } catch (IOException | NumberFormatException e) {
                System.err.println("Could not read budget file, starting with no budget.");
            }
        }
    }

    public synchronized BigDecimal get() {
        return budget;
    }

    public synchronized void set(BigDecimal value) {
        Properties p = new Properties();
        p.setProperty(KEY, value.toPlainString());
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "Personal Expense Tracker settings");
            }
            budget = value;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save budget", e);
        }
    }
}
