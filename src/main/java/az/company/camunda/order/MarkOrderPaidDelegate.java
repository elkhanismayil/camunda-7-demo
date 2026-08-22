package az.company.camunda.order;

import az.company.camunda.events.OrderEventOutbox;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Component("markOrderPaidDelegate")
public class MarkOrderPaidDelegate implements JavaDelegate {

    private final OrderRepository orderRepository;
    private final OrderEventOutbox outbox;

    public MarkOrderPaidDelegate(OrderRepository orderRepository, OrderEventOutbox outbox) {
        this.orderRepository = orderRepository;
        this.outbox = outbox;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String correlationId = (String) execution.getVariable("correlationId");
        orderRepository.findByCorrelationId(correlationId).ifPresent(order -> {
            order.markPaid();
            orderRepository.save(order);

            // Same transaction as the status change and the process state, so
            // the event cannot survive a rollback or go missing after a commit.
            outbox.record(OrderEventOutbox.ORDER_PAID, correlationId,
                    order.getCustomerName(), order.getAmount());
        });
    }
}
