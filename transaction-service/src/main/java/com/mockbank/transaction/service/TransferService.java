package com.mockbank.transaction.service;

import com.mockbank.transaction.client.AccountServiceClient;
import com.mockbank.transaction.controller.dto.TransferRequest;
import com.mockbank.transaction.entity.Transaction;
import com.mockbank.transaction.entity.TransactionStatus;
import com.mockbank.transaction.event.TransactionEventPublisher;
import com.mockbank.transaction.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final TransactionEventPublisher eventPublisher;

    public TransferService(TransactionRepository transactionRepository,
                           AccountServiceClient accountServiceClient,
                           TransactionEventPublisher eventPublisher) {
        this.transactionRepository = transactionRepository;
        this.accountServiceClient = accountServiceClient;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Transaction transfer(TransferRequest request) {
        // 1. Create transaction record with PENDING status
        Transaction transaction = new Transaction();
        transaction.setReferenceId(UUID.randomUUID().toString());
        transaction.setFromAccountId(request.fromAccountId());
        transaction.setToAccountId(request.toAccountId());
        transaction.setAmount(request.amount());
        transaction.setCurrency(request.currency() != null ? request.currency() : "VND");
        transaction.setDescription(request.description());
        transaction = transactionRepository.save(transaction);

        try {
            // 2. Debit from source account (via internal API)
            log.info("Debiting {} from account {}", request.amount(), request.fromAccountId());
            accountServiceClient.debit(request.fromAccountId(), request.amount());

            // 3. Credit to destination account (via internal API)
            log.info("Crediting {} to account {}", request.amount(), request.toAccountId());
            accountServiceClient.credit(request.toAccountId(), request.amount());

            // 4. Mark transaction as COMPLETED
            transaction.setStatus(TransactionStatus.COMPLETED);
            transaction = transactionRepository.save(transaction);
            log.info("Transfer {} completed successfully", transaction.getReferenceId());

            // 5. Publish event to Kafka (async notification)
            eventPublisher.publish(transaction);
        } catch (Exception e) {
            // Mark transaction as FAILED
            transaction.setStatus(TransactionStatus.FAILED);
            transactionRepository.save(transaction);
            log.error("Transfer {} failed: {}", transaction.getReferenceId(), e.getMessage());
            throw new TransferFailedException("Transfer failed: " + e.getMessage());
        }

        return transaction;
    }

    public Transaction getTransaction(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + id));
    }

    public Transaction getTransactionByReference(String referenceId) {
        return transactionRepository.findByReferenceId(referenceId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found: " + referenceId));
    }

    public List<Transaction> getTransactionsByAccount(Long accountId) {
        return transactionRepository.findByFromAccountIdOrToAccountId(accountId, accountId);
    }
}
