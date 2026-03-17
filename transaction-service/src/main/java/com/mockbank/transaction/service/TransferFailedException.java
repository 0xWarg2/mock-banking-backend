package com.mockbank.transaction.service;

public class TransferFailedException extends RuntimeException {
    public TransferFailedException(String message) {
        super(message);
    }
}
