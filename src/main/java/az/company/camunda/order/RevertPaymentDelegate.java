package az.company.camunda.order;

import az.company.camunda.events.OrderEventOutbox;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Compensation handler for Task_MarkPaid. Runs only when triggered by a
 * compensation event (never in the normal happy path) to undo the
 * already-completed payment when a later saga step fails.
 */
@Component("revertPaymentDelegate")
public class RevertPaymentDelegate implements JavaDelegate {

    private final OrderRepository orderRepository;
    private final OrderEventOutbox outbox;

    public RevertPaymentDelegate(OrderRepository orderRepository, OrderEventOutbox outbox) {
        this.orderRepository = orderRepository;
        this.outbox = outbox;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String correlationId = (String) execution.getVariable("correlationId");
        orderRepository.findByCorrelationId(correlationId).ifPresent(order -> {
            order.refund();
            orderRepository.save(order);

            // The refund gets its own event rather than retracting ORDER_PAID:
            // downstream consumers already acted on the payment, so the honest
            // thing to publish is that it was reversed.
            outbox.record(OrderEventOutbox.ORDER_REFUNDED, correlationId,
                    order.getCustomerName(), order.getAmount());
        });
    }
}
