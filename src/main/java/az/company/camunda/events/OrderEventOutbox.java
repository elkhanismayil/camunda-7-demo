package az.company.camunda.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records domain events for later publication.
 *
 * <p>Called from inside Java delegates, so the write joins the engine's own
 * transaction: the order status change, the process state and this event row
 * commit together. Nothing here touches Kafka - that is
 * {@link OutboxPublisher}'s job, and it happens after the commit.
 */
@Component
public class OrderEventOutbox {

    public static final String ORDER_PAID = "ORDER_PAID";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String ORDER_REFUNDED = "ORDER_REFUNDED";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OrderEventOutbox(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void record(String type, String correlationId, String customerName, BigDecimal amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("correlationId", correlationId);
        payload.put("customerName", customerName);
        payload.put("amount", amount);
        payload.put("occurredAt", Instant.now().toString());

        outboxEventRepository.save(new OutboxEvent(correlationId, type, serialize(payload)));
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Failing here rolls back the delegate, and with it the process
            // step - which is correct. An event we cannot serialise is an event
            // we would silently lose.
            throw new IllegalStateException("Could not serialise outbox payload", e);
        }
    }
}
