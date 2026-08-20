package az.company.camunda.order;

import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.runtime.Job;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.assertThat;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.init;

/**
 * Proves the other branch of the event-based gateway: if no PaymentReceived
 * message ever arrives, the 1-hour timer fires and the order is cancelled.
 * The job executor is disabled in the test profile so the timer job is
 * triggered deterministically instead of racing a background thread.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderPaymentTimeoutTest {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ManagementService managementService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProcessEngine processEngine;

    @BeforeEach
    void initAssertions() {
        init(processEngine);
    }

    @AfterEach
    void resetEngineClock() {
        ClockUtil.reset();
    }

    @Test
    void cancelsOrderWhenPaymentNeverArrivesWithinOneHour() {
        String correlationId = UUID.randomUUID().toString();

        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "order-payment-correlation", correlationId,
                Map.of("correlationId", correlationId, "customerName", "Charlie", "amount", BigDecimal.valueOf(99)));

        // Both catch events are parked behind the gateway; the tree reports
        // the gateway's own activity id until one branch fires.
        assertThat(instance).isWaitingAt("Gateway_WaitForEvent");

        ClockUtil.setCurrentTime(Date.from(Instant.now().plus(Duration.ofHours(1).plusMinutes(1))));

        Job timeoutJob = managementService.createJobQuery()
                .processInstanceId(instance.getId())
                .singleResult();
        assertThat(timeoutJob).isNotNull();
        managementService.executeJob(timeoutJob.getId());

        assertThat(instance).isEnded().hasPassed("Task_CancelOrder", "Event_OrderCancelled");

        assertThat(orderRepository.findByCorrelationId(correlationId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }
}
