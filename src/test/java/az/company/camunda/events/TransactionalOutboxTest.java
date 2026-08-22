package az.company.camunda.events;

import az.company.camunda.order.OrderStatus;
import az.company.camunda.order.OrderRepository;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.init;

/**
 * Publishing to Kafka from inside a delegate would be a dual write: the engine
 * commits to Postgres, the producer sends to a broker, and nothing makes those
 * two agree. A crash between them either loses an event that the process
 * believes it emitted, or emits one for a step that rolled back.
 *
 * <p>The outbox removes the second system from the critical path. The event row
 * goes into the same database in the same transaction as the order status and
 * the process state, so the three cannot disagree. Kafka only enters the
 * picture afterwards, from {@link OutboxPublisher}.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionalOutboxTest {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ManagementService managementService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ProcessEngine processEngine;

    @BeforeEach
    void initAssertions() {
        init(processEngine);
    }

    @Test
    void recordsAPaidEventInTheSameTransactionAsTheStatusChange() {
        String correlationId = startAndPay("OutboxCustomer");

        List<OutboxEvent> events = outboxEventRepository.findByAggregateIdOrderByIdAsc(correlationId);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getType()).isEqualTo(OrderEventOutbox.ORDER_PAID);
        assertThat(events.getFirst().getPublishedAt())
                .as("nothing has been sent yet - the publisher runs after the commit")
                .isNull();
        assertThat(events.getFirst().getAggregateId())
                .as("the correlationId doubles as the Kafka key, so one order's events stay ordered")
                .isEqualTo(correlationId);
        assertThat(events.getFirst().getPayload()).contains("\"amount\":10", "OutboxCustomer");

        assertThat(orderRepository.findByCorrelationId(correlationId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    /**
     * A compensated saga does not retract the event it already emitted - by
     * then downstream consumers have acted on it. It emits the correction, and
     * both events sit in the outbox in the order they happened.
     */
    @Test
    void recordsTheReversalAsItsOwnEventRatherThanRetractingThePayment() {
        String correlationId = startAndPay("ShipFail-Customer");
        executeShippingJob(correlationId);

        List<OutboxEvent> events = outboxEventRepository.findByAggregateIdOrderByIdAsc(correlationId);

        assertThat(events).extracting(OutboxEvent::getType)
                .containsExactly(OrderEventOutbox.ORDER_PAID, OrderEventOutbox.ORDER_REFUNDED);

        assertThat(orderRepository.findByCorrelationId(correlationId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
    }

    /**
     * The technical-failure path never commits the shipping step, so it must
     * never produce an event for it either. Only the payment, which did commit,
     * is in the outbox.
     */
    @Test
    void recordsNothingForAStepThatEndedInAnIncident() {
        String correlationId = startAndPay("TechFail-Customer");

        ProcessInstance instance = instanceFor(correlationId);
        for (int attempt = 0; attempt < 10 && retriesLeft(instance) > 0; attempt++) {
            try {
                managementService.executeJob(currentJob(instance).getId());
            } catch (RuntimeException expected) {
                // every attempt fails; the point is what is NOT in the outbox
            }
        }

        assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(correlationId))
                .extracting(OutboxEvent::getType)
                .containsExactly(OrderEventOutbox.ORDER_PAID);
    }

    @Test
    void recordsACancelledEventWhenThePaymentNeverArrives() {
        String correlationId = UUID.randomUUID().toString();
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "order-payment-correlation", correlationId,
                Map.of("correlationId", correlationId, "customerName", "NeverPays", "amount", BigDecimal.TEN));

        Job timeout = managementService.createJobQuery()
                .processInstanceId(instance.getId())
                .singleResult();
        managementService.executeJob(timeout.getId());

        assertThat(outboxEventRepository.findByAggregateIdOrderByIdAsc(correlationId))
                .extracting(OutboxEvent::getType)
                .containsExactly(OrderEventOutbox.ORDER_CANCELLED);
    }

    private String startAndPay(String customerName) {
        String correlationId = UUID.randomUUID().toString();

        runtimeService.startProcessInstanceByKey(
                "order-payment-correlation", correlationId,
                Map.of("correlationId", correlationId, "customerName", customerName, "amount", BigDecimal.TEN));

        runtimeService.createMessageCorrelation("PaymentReceived")
                .processInstanceVariableEquals("correlationId", correlationId)
                .correlateWithResult();

        return correlationId;
    }

    private void executeShippingJob(String correlationId) {
        managementService.executeJob(currentJob(instanceFor(correlationId)).getId());
    }

    private ProcessInstance instanceFor(String correlationId) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(correlationId)
                .singleResult();
    }

    private Job currentJob(ProcessInstance instance) {
        return managementService.createJobQuery()
                .processInstanceId(instance.getId())
                .singleResult();
    }

    private int retriesLeft(ProcessInstance instance) {
        Job job = currentJob(instance);
        return job == null ? 0 : job.getRetries();
    }
}
