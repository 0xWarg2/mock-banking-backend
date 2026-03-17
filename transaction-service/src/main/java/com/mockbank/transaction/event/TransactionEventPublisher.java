package com.mockbank.transaction.event;

import com.mockbank.transaction.entity.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);
    private static final String TOPIC = "transaction-events";

    private final KafkaTemplate<String, TransactionCompletedEvent> kafkaTemplate;
    private final String siteId;

    public TransactionEventPublisher(
            KafkaTemplate<String, TransactionCompletedEvent> kafkaTemplate,
            @Value("${app.site-id:LOCAL}") String siteId) {
        this.kafkaTemplate = kafkaTemplate;
        this.siteId = siteId;
    }

    public void publish(Transaction transaction) {
        var event = new TransactionCompletedEvent(
                transaction.getReferenceId(),
                transaction.getFromAccountId(),
                transaction.getToAccountId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus().name(),
                LocalDateTime.now(),
                siteId
        );

        kafkaTemplate.send(TOPIC, transaction.getReferenceId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish event for transaction {}: {}",
                                transaction.getReferenceId(), ex.getMessage());
                    } else {
                        log.info("Published event for transaction {} to topic {} [site={}]",
                                transaction.getReferenceId(), TOPIC, siteId);
                    }
                });
    }
}
