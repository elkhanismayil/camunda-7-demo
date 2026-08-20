package az.company.camunda.order;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.assertThat;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.init;

/**
 * Proves the correlation mechanism: two order processes are started at the
 * same time, each waiting at the "PaymentReceived" message event. Sending a
 * message correlated to only one order's correlationId must advance that
 * instance and leave the other one untouched.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderPaymentCorrelationTest {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProcessEngine processEngine;

    @BeforeEach
    void initAssertions() {
        init(processEngine);
    }

    @Test
    void correlatesPaymentMessageToTheMatchingOrderOnly() {
        String correlationIdA = UUID.randomUUID().toString();
        String correlationIdB = UUID.randomUUID().toString();

        ProcessInstance instanceA = runtimeService.startProcessInstanceByKey(
                "order-payment-correlation", correlationIdA,
                Map.of("correlationId", correlationIdA, "customerName", "Alice", "amount", BigDecimal.TEN));

        ProcessInstance instanceB = runtimeService.startProcessInstanceByKey(
                "order-payment-correlation", correlationIdB,
                Map.of("correlationId", correlationIdB, "customerName", "Bob", "amount", BigDecimal.ONE));

        // Camunda reports concurrent executions parked behind an event-based
        // gateway under the gateway's own activity id, not the individual
        // catch events, until one branch actually fires.
        assertThat(instanceA).isWaitingAt("Gateway_WaitForEvent");
        assertThat(instanceB).isWaitingAt("Gateway_WaitForEvent");

        runtimeService.createMessageCorrelation("PaymentReceived")
                .processInstanceVariableEquals("correlationId", correlationIdA)
                .correlateWithResult();

        assertThat(instanceA).isEnded().hasPassed("Task_MarkPaid", "Event_OrderCompleted");
        assertThat(instanceB).isNotEnded().isWaitingAt("Gateway_WaitForEvent");

        assertThat(orderRepository.findByCorrelationId(correlationIdA).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(orderRepository.findByCorrelationId(correlationIdB).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }
}
