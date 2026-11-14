package com.geetansh.expensetracker.service;

/** Thrown when user input is invalid; the API turns it into HTTP 400. */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
