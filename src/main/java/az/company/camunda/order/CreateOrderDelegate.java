package az.company.camunda.order;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component("createOrderDelegate")
public class CreateOrderDelegate implements JavaDelegate {

    private final OrderRepository orderRepository;

    public CreateOrderDelegate(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String correlationId = (String) execution.getVariable("correlationId");
        String customerName = (String) execution.getVariable("customerName");
        BigDecimal amount = (BigDecimal) execution.getVariable("amount");

        orderRepository.save(new Order(correlationId, customerName, amount));
    }
}
