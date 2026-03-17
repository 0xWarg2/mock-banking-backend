package com.mockbank.transaction.controller;

import com.mockbank.transaction.controller.dto.TransactionResponse;
import com.mockbank.transaction.controller.dto.TransferRequest;
import com.mockbank.transaction.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransferService transferService;

    public TransactionController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse transfer(@Valid @RequestBody TransferRequest request) {
        return TransactionResponse.from(transferService.transfer(request));
    }

    @GetMapping("/{id}")
    public TransactionResponse getTransaction(@PathVariable Long id) {
        return TransactionResponse.from(transferService.getTransaction(id));
    }

    @GetMapping("/reference/{referenceId}")
    public TransactionResponse getByReference(@PathVariable String referenceId) {
        return TransactionResponse.from(transferService.getTransactionByReference(referenceId));
    }

    @GetMapping("/account/{accountId}")
    public List<TransactionResponse> getByAccount(@PathVariable Long accountId) {
        return transferService.getTransactionsByAccount(accountId).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
