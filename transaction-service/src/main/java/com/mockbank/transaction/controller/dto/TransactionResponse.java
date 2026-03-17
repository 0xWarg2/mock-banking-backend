package com.mockbank.transaction.controller.dto;

import com.mockbank.transaction.entity.Transaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionResponse(
        Long id,
        String referenceId,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        String currency,
        String status,
        String description,
        LocalDateTime createdAt
) {
    public static TransactionResponse from(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getReferenceId(),
                tx.getFromAccountId(),
                tx.getToAccountId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getStatus().name(),
                tx.getDescription(),
                tx.getCreatedAt()
        );
    }
}
