package com.geetansh.expensetracker.repository;

import com.geetansh.expensetracker.model.Transaction;

import java.util.List;
import java.util.Optional;

/** Storage abstraction: swap the CSV file for a database without touching the service layer. */
public interface TransactionRepository {
    List<Transaction> findAll();

    Optional<Transaction> findById(long id);

    /** Inserts (id == 0) or replaces (id != 0) and returns the stored entry. */
    Transaction save(Transaction transaction);

    boolean deleteById(long id);
}
