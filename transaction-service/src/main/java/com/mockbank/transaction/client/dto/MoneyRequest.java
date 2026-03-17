package com.mockbank.transaction.client.dto;

import java.math.BigDecimal;

public record MoneyRequest(BigDecimal amount) {}
