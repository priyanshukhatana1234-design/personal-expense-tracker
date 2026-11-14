package com.geetansh.expensetracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One income or expense entry. Immutable; an id of 0 means "not saved yet".
 */
public record Transaction(
        long id,
        TransactionType type,
        String title,
        String category,
        BigDecimal amount,
        LocalDate date,
        String note) {

    public Transaction withId(long newId) {
        return new Transaction(newId, type, title, category, amount, date, note);
    }
}
