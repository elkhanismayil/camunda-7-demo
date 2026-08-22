package az.company.camunda.events;

import az.company.camunda.order.Order;
import az.company.camunda.order.OrderService;
import az.company.camunda.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The inbound half: a payment event on Kafka correlates the
 * {@code PaymentReceived} message to whichever instance is waiting for it.
 * Same call the REST endpoint makes - the transport changed, the process did
 * not.
 *
 * <p>Two things make this harder than it looks, and both are consequences of
 * at-least-once delivery:
 *
 * <ul>
 *   <li><b>Redelivery.</b> The same event can arrive twice. Correlating twice
 *       would either advance the process twice or blow up, so the handler
 *       checks the order's own state first and treats an already-paid order as
 *       a no-op. The order status is the deduplication key.</li>
 *   <li><b>Arriving early.</b> A correlation failure does not mean the event
 *       is bad - it usually means the instance has not reached the message
 *       event yet. A high-risk order sitting in manual review is exactly this
 *       case: it is alive, it will accept the payment later, it just cannot
 *       right now. So the handler throws, the container retries with backoff,
 *       and only a persistently uncorrelatable event ends up on the dead letter
 *       topic.</li>
 * </ul>
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final OrderService orderService;

    public PaymentEventConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @KafkaListener(
            topics = "${demo.kafka.payment-events-topic}",
            groupId = "${demo.kafka.consumer-group}")
    public void onPaymentEvent(String correlationId) {
        Optional<Order> order = orderService.findByCorrelationId(correlationId);

        if (order.isEmpty()) {
            // Nothing this consumer can ever do about it - retrying would only
            // delay the inevitable, so let it go straight to the DLT.
            log.warn("Payment event for unknown order {}", correlationId);
            throw new UnknownOrderException(correlationId);
        }

        if (order.get().getStatus() != OrderStatus.AWAITING_PAYMENT) {
            log.info("Ignoring duplicate payment event for order {} (already {})",
                    correlationId, order.get().getStatus());
            return;
        }

        boolean correlated = orderService.notifyPaymentReceived(correlationId);

        if (!correlated) {
            log.warn("Order {} is not waiting for a payment message yet; will retry", correlationId);
            throw new NotWaitingForPaymentException(correlationId);
        }

        log.info("Correlated payment event for order {}", correlationId);
    }

    /** Retryable: the instance exists but has not reached the message event. */
    static class NotWaitingForPaymentException extends RuntimeException {
        NotWaitingForPaymentException(String correlationId) {
            super("Order " + correlationId + " is not waiting for PaymentReceived");
        }
    }

    /** Not retryable: there is no such order. */
    static class UnknownOrderException extends RuntimeException {
        UnknownOrderException(String correlationId) {
            super("No order with correlationId " + correlationId);
        }
    }
}
