package com.mockbank.notification.listener;

import com.mockbank.notification.event.TransactionCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventListener.class);

    @KafkaListener(topics = "transaction-events", groupId = "${spring.kafka.consumer.group-id}")
    public void onTransactionCompleted(TransactionCompletedEvent event) {
        if (event.siteId() != null) {
            MDC.put("siteId", event.siteId());
        }
        try {
            log.info("=== NOTIFICATION [site={}] ===", event.siteId());
            log.info("Transaction {} - Status: {}", event.referenceId(), event.status());
            log.info("From account {} -> To account {}", event.fromAccountId(), event.toAccountId());
            log.info("Amount: {} {}", event.amount(), event.currency());

            // Mock SMS notification
            log.info("[MOCK SMS] Sent to account {}: Your transfer of {} {} to account {} is {}",
                    event.fromAccountId(), event.amount(), event.currency(),
                    event.toAccountId(), event.status().toLowerCase());

            // Mock email notification
            log.info("[MOCK EMAIL] Sent to account {}: You received {} {} from account {}",
                    event.toAccountId(), event.amount(), event.currency(),
                    event.fromAccountId());

            log.info("====================");
        } finally {
            MDC.remove("siteId");
        }
    }
}
