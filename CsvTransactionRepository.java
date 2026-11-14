package com.geetansh.expensetracker.repository;

import com.geetansh.expensetracker.model.Transaction;
import com.geetansh.expensetracker.model.TransactionType;
import com.geetansh.expensetracker.util.Csv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps all transactions in memory and writes them to a CSV file after every change.
 * The file is written to a temp file first and then moved into place, so a crash
 * mid-write cannot corrupt existing data.
 */
public class CsvTransactionRepository implements TransactionRepository {
    private static final String HEADER = "id,type,title,category,amount,date,note";

    private final Path file;
    private Map<Long, Transaction> items = new LinkedHashMap<>();
    private long nextId = 1;

    public CsvTransactionRepository(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) continue;
                try {
                    List<String> f = Csv.parseLine(line);
                    Transaction t = new Transaction(
                            Long.parseLong(f.get(0)),
                            TransactionType.valueOf(f.get(1)),
                            f.get(2),
                            f.get(3),
                            new BigDecimal(f.get(4)),
                            LocalDate.parse(f.get(5)),
                            f.size() > 6 ? f.get(6) : "");
                    items.put(t.id(), t);
                    nextId = Math.max(nextId, t.id() + 1);
                } catch (RuntimeException e) {
                    System.err.println("Skipping malformed line " + (i + 1) + " in " + file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    @Override
    public synchronized List<Transaction> findAll() {
        return new ArrayList<>(items.values());
    }

    @Override
    public synchronized Optional<Transaction> findById(long id) {
        return Optional.ofNullable(items.get(id));
    }

    @Override
    public synchronized Transaction save(Transaction t) {
        long id = t.id() == 0 ? nextId : t.id();
        Transaction saved = t.withId(id);
        Map<Long, Transaction> next = new LinkedHashMap<>(items);
        next.put(id, saved);
        write(next);               // only commit in memory if the file write worked
        items = next;
        nextId = Math.max(nextId, id + 1);
        return saved;
    }

    @Override
    public synchronized boolean deleteById(long id) {
        if (!items.containsKey(id)) return false;
        Map<Long, Transaction> next = new LinkedHashMap<>(items);
        next.remove(id);
        write(next);
        items = next;
        return true;
    }

    private void write(Map<Long, Transaction> data) {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (Transaction t : data.values()) {
            lines.add(Csv.join(List.of(
                    Long.toString(t.id()), t.type().name(), t.title(), t.category(),
                    t.amount().toPlainString(), t.date().toString(), t.note() == null ? "" : t.note())));
        }
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "expenses", ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save " + file, e);
        }
    }
}
