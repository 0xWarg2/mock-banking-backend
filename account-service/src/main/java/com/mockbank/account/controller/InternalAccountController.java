package com.mockbank.account.controller;

import com.mockbank.account.controller.dto.AccountResponse;
import com.mockbank.account.controller.dto.MoneyRequest;
import com.mockbank.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/accounts")
public class InternalAccountController {

    private final AccountService accountService;

    public InternalAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable Long id) {
        return AccountResponse.from(accountService.getAccountById(id));
    }

    @PostMapping("/{id}/debit")
    public AccountResponse debit(@PathVariable Long id, @Valid @RequestBody MoneyRequest request) {
        return AccountResponse.from(accountService.debit(id, request.amount()));
    }

    @PostMapping("/{id}/credit")
    public AccountResponse credit(@PathVariable Long id, @Valid @RequestBody MoneyRequest request) {
        return AccountResponse.from(accountService.credit(id, request.amount()));
    }
}
