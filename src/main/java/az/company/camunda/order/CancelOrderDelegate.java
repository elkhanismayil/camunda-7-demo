package az.company.camunda.order;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Component("cancelOrderDelegate")
public class CancelOrderDelegate implements JavaDelegate {

    private final OrderRepository orderRepository;

    public CancelOrderDelegate(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String correlationId = (String) execution.getVariable("correlationId");
        orderRepository.findByCorrelationId(correlationId).ifPresent(order -> {
            order.cancel();
            orderRepository.save(order);
        });
    }
}
