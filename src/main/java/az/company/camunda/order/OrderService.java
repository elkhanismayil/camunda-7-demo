package az.company.camunda.order;

import org.camunda.bpm.engine.MismatchingMessageCorrelationException;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
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
    private static final String DELETE_REASON = "Order soft-deleted";

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

    /**
     * Hides the order and terminates the process behind it. Leaving the
     * instance running would let a later PaymentReceived message flip a deleted
     * order to PAID, which is exactly the inconsistency soft delete is supposed
     * to avoid.
     *
     * @return true if a live order was found and deleted
     */
    @Transactional
    public boolean softDelete(String correlationId) {
        Optional<Order> order = orderRepository.findByCorrelationIdAndDeletedAtIsNull(correlationId);
        if (order.isEmpty()) {
            return false;
        }

        // Camunda does not enforce unique business keys, so this is a list and
        // not a singleResult() - the latter would throw on a duplicate rather
        // than clean it up. IfExists tolerates an instance that finished on its
        // own between the query and the delete.
        List<String> instanceIds = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(correlationId)
                .list()
                .stream()
                .map(ProcessInstance::getId)
                .toList();

        if (!instanceIds.isEmpty()) {
            runtimeService.deleteProcessInstancesIfExists(instanceIds, DELETE_REASON, false, true, false);
        }

        order.get().markDeleted();
        orderRepository.save(order.get());
        return true;
    }

    public Optional<Order> findByCorrelationId(String correlationId) {
        return orderRepository.findByCorrelationIdAndDeletedAtIsNull(correlationId);
    }

    public Page<Order> findOrders(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return orderRepository.findAllByDeletedAtIsNull(pageRequest);
    }
}
