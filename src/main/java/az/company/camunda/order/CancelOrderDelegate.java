package az.company.camunda.order;

import az.company.camunda.events.OrderEventOutbox;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Component("cancelOrderDelegate")
public class CancelOrderDelegate implements JavaDelegate {

    private final OrderRepository orderRepository;
    private final OrderEventOutbox outbox;

    public CancelOrderDelegate(OrderRepository orderRepository, OrderEventOutbox outbox) {
        this.orderRepository = orderRepository;
        this.outbox = outbox;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String correlationId = (String) execution.getVariable("correlationId");
        orderRepository.findByCorrelationId(correlationId).ifPresent(order -> {
            order.cancel();
            orderRepository.save(order);

            outbox.record(OrderEventOutbox.ORDER_CANCELLED, correlationId,
                    order.getCustomerName(), order.getAmount());
        });
    }
}
