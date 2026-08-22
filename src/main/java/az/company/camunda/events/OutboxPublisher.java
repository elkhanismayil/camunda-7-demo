package az.company.camunda.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drains the outbox to Kafka after the engine transaction has committed.
 *
 * <p>This is where at-least-once delivery comes from, and it is worth being
 * precise about why. The broker acknowledgement and the {@code publishedAt}
 * update are still two systems: a crash in between means the row is republished
 * on the next tick and a consumer sees the event twice. That is a deliberate
 * trade - the alternative, marking the row first, loses events instead. Losing
 * is worse than repeating, so consumers must be idempotent. The Kafka key is
 * the correlationId precisely so a consumer can deduplicate on it.
 */
@Component
@Profile("!test")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private static final long ACK_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           @Value("${demo.kafka.order-events-topic}") String topic) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${demo.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByIdAsc();

        for (OutboxEvent event : pending) {
            try {
                // Blocking on the acknowledgement is the point: without it we
                // would mark the row published while the record is still only
                // in the producer's buffer.
                kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
                        .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                event.markPublished();
                log.info("Published {} for order {}", event.getType(), event.getAggregateId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Stop at the first failure rather than skipping past it -
                // continuing would publish a later event for the same order
                // before an earlier one, which is exactly what keying by
                // correlationId is supposed to prevent.
                log.warn("Outbox publication stalled at event {}; retrying next tick", event.getId(), e);
                return;
            }
        }
    }
}
