package com.mockbank.transaction.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionCompletedEvent(
        String referenceId,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        String currency,
        String status,
        LocalDateTime timestamp,
        String siteId
) {}
