package az.company.camunda.order;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
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
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.assertThat;
import static org.camunda.bpm.engine.test.assertions.ProcessEngineTests.init;

/**
 * Proves the saga/compensation pattern: Task_MarkPaid is a compensable
 * activity (Task_RevertPayment attached via a boundary compensation
 * event). When the next step (Task_NotifyShipping) fails with a BPMN
 * error, the boundary error event throws compensation, which runs the
 * revert handler and flips the already-PAID order back to REFUNDED -
 * instead of leaving it stuck in an inconsistent PAID state.
 *
 * <p>Task_NotifyShipping is asyncBefore, so correlating the payment only
 * gets as far as committing the payment and creating a job. The job
 * executor is off in tests, so each test drives that job explicitly.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentReversalSagaTest {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ManagementService managementService;

    @Autowired
    private ExternalTaskService externalTaskService;

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
        String correlationId = startAndPay("ShipFail-Customer");
        ProcessInstance instance = processInstanceFor(correlationId);

        executeShippingJob(instance);

        assertThat(instance).isEnded()
                .hasPassed("Task_MarkPaid", "Task_NotifyShipping", "Task_RevertPayment", "Event_PaymentReversed");

        assertThat(statusOf(correlationId)).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void completesNormallyWhenShippingNotificationSucceeds() {
        String correlationId = startAndPay("HappyCustomer");
        ProcessInstance instance = processInstanceFor(correlationId);

        executeShippingJob(instance);
        completeInvoiceTask();

        assertThat(instance).isEnded()
                .hasPassed("Task_MarkPaid", "Task_NotifyShipping", "Task_GenerateInvoice", "Event_OrderCompleted");

        assertThat(statusOf(correlationId)).isEqualTo(OrderStatus.PAID);
    }

    /**
     * The asyncBefore marker on Task_NotifyShipping is a transaction
     * boundary: the payment must already be durably committed before the
     * external shipping call is even attempted, so a crash during that
     * call can never lose the fact that the customer paid.
     */
    @Test
    void commitsThePaymentBeforeAttemptingTheShippingCall() {
        String correlationId = startAndPay("HappyCustomer");
        ProcessInstance instance = processInstanceFor(correlationId);

        assertThat(statusOf(correlationId)).isEqualTo(OrderStatus.PAID);
        assertThat(instance).isNotEnded();

        Job pendingShippingCall = managementService.createJobQuery()
                .processInstanceId(instance.getId())
                .singleResult();
        org.assertj.core.api.Assertions.assertThat(pendingShippingCall).isNotNull();
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

    private ProcessInstance processInstanceFor(String correlationId) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(correlationId)
                .singleResult();
    }

    private void executeShippingJob(ProcessInstance instance) {
        Job job = managementService.createJobQuery()
                .processInstanceId(instance.getId())
                .singleResult();
        managementService.executeJob(job.getId());
    }

    /**
     * The happy path ends on an external task, which is a wait state: the
     * process only reaches its end event once a worker reports the invoice
     * back. The compensation path never gets this far.
     */
    private void completeInvoiceTask() {
        LockedExternalTask invoiceTask = externalTaskService.fetchAndLock(1, "test-worker")
                .topic("invoice-generation", 10_000L)
                .execute()
                .getFirst();
        externalTaskService.complete(invoiceTask.getId(), "test-worker");
    }

    private OrderStatus statusOf(String correlationId) {
        return orderRepository.findByCorrelationId(correlationId).orElseThrow().getStatus();
    }
}
