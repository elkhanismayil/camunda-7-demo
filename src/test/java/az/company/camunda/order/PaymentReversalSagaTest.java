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
 * Proves the saga/compensation pattern: Task_MarkPaid is a compensable
 * activity (Task_RevertPayment attached via a boundary compensation
 * event). When the next step (Task_NotifyShipping) fails with a BPMN
 * error, the boundary error event throws compensation, which runs the
 * revert handler and flips the already-PAID order back to REFUNDED -
 * instead of leaving it stuck in an inconsistent PAID state.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentReversalSagaTest {

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
    void revertsPaymentWhenShippingNotificationFails() {
        String correlationId = UUID.randomUUID().toString();

        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "order-payment-correlation", correlationId,
                Map.of("correlationId", correlationId, "customerName", "ShipFail-Customer", "amount", BigDecimal.TEN));

        runtimeService.createMessageCorrelation("PaymentReceived")
                .processInstanceVariableEquals("correlationId", correlationId)
                .correlateWithResult();

        assertThat(instance).isEnded()
                .hasPassed("Task_MarkPaid", "Task_NotifyShipping", "Task_RevertPayment", "Event_PaymentReversed");

        assertThat(orderRepository.findByCorrelationId(correlationId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void completesNormallyWhenShippingNotificationSucceeds() {
        String correlationId = UUID.randomUUID().toString();

        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "order-payment-correlation", correlationId,
                Map.of("correlationId", correlationId, "customerName", "HappyCustomer", "amount", BigDecimal.TEN));

        runtimeService.createMessageCorrelation("PaymentReceived")
                .processInstanceVariableEquals("correlationId", correlationId)
                .correlateWithResult();

        assertThat(instance).isEnded()
                .hasPassed("Task_MarkPaid", "Task_NotifyShipping", "Event_OrderCompleted");

        assertThat(orderRepository.findByCorrelationId(correlationId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }
}
