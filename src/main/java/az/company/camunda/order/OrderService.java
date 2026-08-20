package az.company.camunda.order;

import org.camunda.bpm.engine.MismatchingMessageCorrelationException;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared between the REST API and the Thymeleaf pages so both drive the
 * exact same correlation logic against the process engine.
 */
@Service
public class OrderService {

    private static final String PROCESS_KEY = "order-payment-correlation";
    private static final String PAYMENT_MESSAGE = "PaymentReceived";

    private final RuntimeService runtimeService;
    private final OrderRepository orderRepository;

    public OrderService(RuntimeService runtimeService, OrderRepository orderRepository) {
        this.runtimeService = runtimeService;
        this.orderRepository = orderRepository;
    }

    public ProcessInstance createOrder(String customerName, BigDecimal amount) {
        String correlationId = UUID.randomUUID().toString();

        Map<String, Object> variables = new HashMap<>();
        variables.put("correlationId", correlationId);
        variables.put("customerName", customerName);
        variables.put("amount", amount);

        return runtimeService.startProcessInstanceByKey(PROCESS_KEY, correlationId, variables);
    }

    /** @return true if a waiting process instance was found and correlated */
    public boolean notifyPaymentReceived(String correlationId) {
        try {
            runtimeService.createMessageCorrelation(PAYMENT_MESSAGE)
                    .processInstanceVariableEquals("correlationId", correlationId)
                    .correlateWithResult();
            return true;
        } catch (MismatchingMessageCorrelationException e) {
            return false;
        }
    }

    public Optional<Order> findByCorrelationId(String correlationId) {
        return orderRepository.findByCorrelationId(correlationId);
    }

    public Page<Order> findOrders(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return orderRepository.findAll(pageRequest);
    }
}
