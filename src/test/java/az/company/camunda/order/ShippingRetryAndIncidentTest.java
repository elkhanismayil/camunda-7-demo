package az.company.camunda.order;

import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.Incident;
import org.camunda.bpm.engine.runtime.Job;
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
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.init;

/**
 * The counterpart to {@link PaymentReversalSagaTest}: a technical failure
 * is not a modelled business outcome, so it must not trigger compensation.
 * Instead the job executor retries it per failedJobRetryTimeCycle, and once
 * retries are exhausted the engine raises an incident - the process stays
 * alive and parked, waiting for an operator to fix the cause and reset the
 * retries in Cockpit, rather than silently refunding a paying customer.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShippingRetryAndIncidentTest {

    private static final int MAX_ATTEMPTS = 10;

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

    @Test
    void retriesTechnicalFailuresThenRaisesAnIncidentWithoutCompensating() {
        String correlationId = UUID.randomUUID().toString();

        runtimeService.startProcessInstanceByKey(
                "order-payment-correlation", correlationId,
                Map.of("correlationId", correlationId, "customerName", "TechFail-Customer", "amount", BigDecimal.TEN));

        runtimeService.createMessageCorrelation("PaymentReceived")
                .processInstanceVariableEquals("correlationId", correlationId)
                .correlateWithResult();

        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(correlationId)
                .singleResult();

        int attempts = 0;
        while (attempts < MAX_ATTEMPTS && retriesLeft(instance) > 0) {
            attempts++;
            try {
                managementService.executeJob(currentJob(instance).getId());
            } catch (RuntimeException expected) {
                // the delegate's technical failure surfaces here on every attempt
            }
        }

        assertThat(attempts)
                .as("the job should have been retried more than once before giving up")
                .isGreaterThan(1);
        assertThat(retriesLeft(instance)).isZero();

        Incident incident = processEngine.getRuntimeService().createIncidentQuery()
                .processInstanceId(instance.getId())
                .singleResult();
        assertThat(incident).isNotNull();
        assertThat(incident.getIncidentType()).isEqualTo("failedJob");
        assertThat(incident.getActivityId()).isEqualTo("Task_NotifyShipping");

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult())
                .as("a technical failure must not end the process")
                .isNotNull();

        assertThat(orderRepository.findByCorrelationId(correlationId).orElseThrow().getStatus())
                .as("compensation is for modelled business errors, not broken connections")
                .isEqualTo(OrderStatus.PAID);
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
