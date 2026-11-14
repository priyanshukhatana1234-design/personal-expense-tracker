package com.geetansh.expensetracker.service;

/** Thrown when a transaction id does not exist; the API turns it into HTTP 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
