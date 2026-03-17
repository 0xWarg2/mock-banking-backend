package com.mockbank.account.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank(message = "Owner name is required")
        String ownerName,

        @PositiveOrZero(message = "Initial balance must be >= 0")
        BigDecimal initialBalance,

        String currency
) {}
