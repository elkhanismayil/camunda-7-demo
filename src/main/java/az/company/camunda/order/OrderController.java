package az.company.camunda.order;

import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Demonstrates Camunda message correlation: many "order-payment-correlation"
 * process instances can be running concurrently, each waiting at the
 * "PaymentReceived" message catch event. Correlation lets an external
 * notification (POST .../payment) reach the exact one waiting instance by
 * matching a business correlationId, without the caller knowing the process
 * instance id.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public Map<String, String> createOrder(@RequestBody CreateOrderRequest request) {
        ProcessInstance instance = orderService.createOrder(request.customerName(), request.amount());

        return Map.of(
                "correlationId", instance.getBusinessKey(),
                "processInstanceId", instance.getId()
        );
    }

    @PostMapping("/{correlationId}/payment")
    public ResponseEntity<Void> notifyPaymentReceived(@PathVariable String correlationId) {
        boolean correlated = orderService.notifyPaymentReceived(correlationId);
        return correlated ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/{correlationId}")
    public ResponseEntity<Order> getOrder(@PathVariable String correlationId) {
        return orderService.findByCorrelationId(correlationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateOrderRequest(String customerName, BigDecimal amount) {
    }
}
