package com.mockbank.transaction.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record TransferRequest(
        @NotNull(message = "Source account ID is required")
        Long fromAccountId,

        @NotNull(message = "Destination account ID is required")
        Long toAccountId,

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be positive")
        BigDecimal amount,

        String currency,

        String description
) {}
