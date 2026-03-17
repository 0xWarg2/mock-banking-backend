package com.mockbank.account.job;

import com.mockbank.account.entity.Account;
import com.mockbank.account.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@Component
public class BalanceReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(BalanceReconciliationJob.class);

    private final AccountRepository accountRepository;
    private final StringRedisTemplate redisTemplate;
    private final String siteId;

    public BalanceReconciliationJob(
            AccountRepository accountRepository,
            StringRedisTemplate redisTemplate,
            @Value("${app.site-id:LOCAL}") String siteId) {
        this.accountRepository = accountRepository;
        this.redisTemplate = redisTemplate;
        this.siteId = siteId;
    }

    @Scheduled(fixedRate = 60000) // every 60 seconds
    public void reconcileBalances() {
        String lockKey = "job:reconciliation:" + siteId;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, siteId, Duration.ofSeconds(55));

        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Reconciliation job skipped - lock already held for site {}", siteId);
            return;
        }

        MDC.put("siteId", siteId);
        try {
            log.info("=== Balance Reconciliation Job Started [site={}] ===", siteId);

            List<Account> accounts = accountRepository.findAll();
            int issuesFound = 0;

            for (Account account : accounts) {
                // Check for negative balances
                if (account.getBalance().compareTo(BigDecimal.ZERO) < 0) {
                    log.warn("RECONCILIATION ISSUE: Account {} has negative balance: {}",
                            account.getAccountNumber(), account.getBalance());
                    issuesFound++;
                }
            }

            log.info("=== Balance Reconciliation Complete [site={}]: {} accounts checked, {} issues found ===",
                    siteId, accounts.size(), issuesFound);
        } finally {
            MDC.remove("siteId");
        }
    }
}
