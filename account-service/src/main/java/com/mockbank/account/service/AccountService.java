package com.mockbank.account.service;

import com.mockbank.account.controller.dto.CreateAccountRequest;
import com.mockbank.account.entity.Account;
import com.mockbank.account.entity.AccountStatus;
import com.mockbank.account.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    @Cacheable(value = "accounts", key = "#id")
    public Account getAccountById(Long id) {
        log.info("Cache MISS - fetching account {} from database", id);
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + id));
    }

    public Account getAccountByNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountNumber));
    }

    @Transactional
    public Account createAccount(CreateAccountRequest request) {
        Account account = new Account();
        account.setAccountNumber(generateAccountNumber());
        account.setOwnerName(request.ownerName());
        account.setBalance(request.initialBalance() != null ? request.initialBalance() : BigDecimal.ZERO);
        account.setCurrency(request.currency() != null ? request.currency() : "VND");
        return accountRepository.save(account);
    }

    @Cacheable(value = "balances", key = "#id")
    public BigDecimal getBalance(Long id) {
        log.info("Cache MISS - fetching balance for account {} from database", id);
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + id))
                .getBalance();
    }

    @Transactional
    @CacheEvict(value = {"accounts", "balances"}, key = "#id")
    public Account debit(Long id, BigDecimal amount) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + id));
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance for account: " + id);
        }
        account.setBalance(account.getBalance().subtract(amount));
        log.info("Cache EVICT - account {} debited {}", id, amount);
        return accountRepository.save(account);
    }

    @Transactional
    @CacheEvict(value = {"accounts", "balances"}, key = "#id")
    public Account credit(Long id, BigDecimal amount) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + id));
        account.setBalance(account.getBalance().add(amount));
        log.info("Cache EVICT - account {} credited {}", id, amount);
        return accountRepository.save(account);
    }

    @Transactional
    @CacheEvict(value = "accounts", key = "#id")
    public void closeAccount(Long id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + id));
        account.setStatus(AccountStatus.CLOSED);
        accountRepository.save(account);
    }

    private String generateAccountNumber() {
        String number;
        do {
            number = String.format("%010d", new Random().nextInt(1_000_000_000));
        } while (accountRepository.existsByAccountNumber(number));
        return number;
    }
}
