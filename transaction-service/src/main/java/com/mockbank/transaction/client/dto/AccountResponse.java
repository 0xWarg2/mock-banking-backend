package com.mockbank.transaction.client.dto;

import java.math.BigDecimal;

public record AccountResponse(
        Long id,
        String accountNumber,
        String ownerName,
        BigDecimal balance,
        String currency,
        String status
) {}
