package com.mockbank.transaction.repository;

import com.mockbank.transaction.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByReferenceId(String referenceId);

    List<Transaction> findByFromAccountIdOrToAccountId(Long fromAccountId, Long toAccountId);
}
