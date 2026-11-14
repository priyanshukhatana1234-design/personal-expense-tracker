package com.geetansh.expensetracker.model;

import java.math.BigDecimal;
import java.util.Map;

/** Totals for one month (or for all time when month is "all"). */
public record Summary(
        String month,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance,
        BigDecimal budget,
        int count,
        Map<String, BigDecimal> expenseByCategory) {
}
