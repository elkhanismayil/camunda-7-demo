package az.company.camunda.events;

import az.company.camunda.order.OrderRepository;
import az.company.camunda.order.OrderStatus;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.init;

/**
 * The listener is invoked directly here, with no broker involved. That is not
 * a shortcut around integration testing - it is where the interesting logic
 * actually lives. Kafka guarantees at-least-once delivery and nothing about
 * ordering relative to the process, so what matters is how the handler behaves
 * when an event arrives twice, arrives too early, or arrives for nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentEventConsumerTest {

    @Autowired
    private PaymentEventConsumer consumer;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProcessEngine processEngine;

    @BeforeEach
    void initAssertions() {
        init(processEngine);
    }

    @Test
    void correlatesThePaymentMessageForAWaitingOrder() {
        String correlationId = startOrder("KafkaCustomer", BigDecimal.TEN);

        consumer.onPaymentEvent(correlationId);

        assertThat(orderRepository.findByCorrelationId(correlationId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    /**
     * At-least-once delivery means the same event can show up again after the
     * process has already moved on. Correlating a second time would fail, so
     * the handler uses the order's own status as its deduplication key and
     * treats the repeat as a no-op.
     */
    @Test
    void treatsARedeliveredEventAsANoOp() {
        String correlationId = startOrder("KafkaCustomer", BigDecimal.TEN);
        consumer.onPaymentEvent(correlationId);

        assertThatCode(() -> consumer.onPaymentEvent(correlationId))
                .as("a duplicate must not fail the listener - that would retry it forever")
                .doesNotThrowAnyException();

        assertThat(orderRepository.findByCorrelationId(correlationId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    /**
     * The race that actually happens in production: a high-value order is
     * parked in manual review, so it is alive and will accept the payment -
     * just not yet. Failing here is correct, because the container's backoff
     * turns it into a retry rather than a lost payment.
     */
    @Test
    void failsRetryablyWhenTheInstanceHasNotReachedTheMessageEventYet() {
        String correlationId = startOrder("HighValueCustomer", new BigDecimal("5000"));

        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(correlationId)
                .singleResult();
        assertThat(taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .taskDefinitionKey("Task_ManualReview")
                .count())
                .as("a HIGH risk order waits in review, not at the payment event")
                .isEqualTo(1);

        assertThatThrownBy(() -> consumer.onPaymentEvent(correlationId))
                .isInstanceOf(PaymentEventConsumer.NotWaitingForPaymentException.class);

        assertThat(orderRepository.findByCorrelationId(correlationId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.AWAITING_PAYMENT);

        // Once the reviewer is done the very same event correlates fine, which
        // is why retrying is the right response and dead-lettering is not.
        taskService.complete(taskService.createTaskQuery()
                .processInstanceId(instance.getId()).singleResult().getId());

        consumer.onPaymentEvent(correlationId);

        assertThat(orderRepository.findByCorrelationId(correlationId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    /**
     * The opposite case: no order will ever exist for this key, so retrying is
     * pointless. KafkaConfig marks this exception non-retryable so the record
     * goes straight to the dead letter topic instead of blocking its partition.
     */
    @Test
    void failsNonRetryablyForAnUnknownOrder() {
        assertThatThrownBy(() -> consumer.onPaymentEvent(UUID.randomUUID().toString()))
                .isInstanceOf(PaymentEventConsumer.UnknownOrderException.class);
    }

    private String startOrder(String customerName, BigDecimal amount) {
        String correlationId = UUID.randomUUID().toString();

        runtimeService.startProcessInstanceByKey(
                "order-payment-correlation", correlationId,
                Map.of("correlationId", correlationId, "customerName", customerName, "amount", amount));

        return correlationId;
    }
}
