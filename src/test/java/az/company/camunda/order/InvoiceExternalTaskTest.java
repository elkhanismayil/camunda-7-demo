package az.company.camunda.order;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.externaltask.ExternalTask;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.camunda.bpm.engine.runtime.Incident;
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
 * Task_GenerateInvoice is declared as {@code camunda:type="external"} with a
 * topic instead of a delegate, which inverts who calls whom: the engine parks
 * the instance and publishes the work, and a worker pulls it when it is ready.
 *
 * <p>These tests drive the engine-side ExternalTaskService rather than the REST
 * client, so no HTTP server or polling thread is involved and every step is
 * deterministic - but the operations (fetchAndLock, complete, handleFailure)
 * are exactly the ones {@link InvoiceWorker} calls over REST at runtime.
 */
@SpringBootTest
@ActiveProfiles("test")
class InvoiceExternalTaskTest {

    private static final String TOPIC = "invoice-generation";
    private static final long LOCK_DURATION = 10_000L;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ManagementService managementService;

    @Autowired
    private ExternalTaskService externalTaskService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private ProcessEngine processEngine;

    @BeforeEach
    void initAssertions() {
        init(processEngine);
    }

    /**
     * An external task is a wait state. Unlike a delegate, reaching the
     * activity does not run any of our code - the instance simply stops there
     * until somebody reports back, which is why a worker can be down for an
     * hour without anything failing.
     */
    @Test
    void parksTheInstanceUntilAWorkerReportsBack() {
        ProcessInstance instance = startPaidOrderAwaitingInvoice();

        ExternalTask published = externalTaskService.createExternalTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult();

        assertThat(published).isNotNull();
        assertThat(published.getTopicName()).isEqualTo(TOPIC);
        assertThat(published.getActivityId()).isEqualTo("Task_GenerateInvoice");
        assertThat(published.getWorkerId())
                .as("nothing is locked until a worker actually fetches it")
                .isNull();

        LockedExternalTask fetched = fetchOne("invoice-worker-1");
        assertThat(fetched.getVariables())
                .as("a worker gets the variables it asked for, not a database handle")
                .containsKey("correlationId");

        externalTaskService.complete(fetched.getId(), "invoice-worker-1",
                Map.of("invoiceNumber", "INV-2026-000042"));

        org.camunda.bpm.engine.test.assertions.ProcessEngineTests.assertThat(instance)
                .isEnded()
                .hasPassed("Task_GenerateInvoice", "Event_OrderCompleted");

        assertThat(historicVariable(instance, "invoiceNumber"))
                .as("the worker's result flows back into the process as a variable")
                .isEqualTo("INV-2026-000042");
    }

    /**
     * fetchAndLock is the whole concurrency story: the lock is what lets you
     * run ten identical workers against one topic without any of them doing
     * the same piece of work twice.
     */
    @Test
    void locksAFetchedTaskSoACompetingWorkerCannotTakeIt() {
        ProcessInstance instance = startPaidOrderAwaitingInvoice();

        LockedExternalTask takenByFirst = fetchOne("invoice-worker-1");
        assertThat(takenByFirst.getWorkerId()).isEqualTo("invoice-worker-1");

        List<LockedExternalTask> secondAttempt = externalTaskService.fetchAndLock(1, "invoice-worker-2")
                .topic(TOPIC, LOCK_DURATION)
                .execute();

        assertThat(secondAttempt)
                .as("the task is locked to worker 1 for the lock duration")
                .isEmpty();

        // Releasing the lock explicitly is how a worker hands work back when it
        // realises it cannot finish it - the task becomes fetchable again
        // immediately instead of after the lock expires.
        externalTaskService.unlock(takenByFirst.getId());

        List<LockedExternalTask> afterUnlock = externalTaskService.fetchAndLock(1, "invoice-worker-2")
                .topic(TOPIC, LOCK_DURATION)
                .execute();
        assertThat(afterUnlock).hasSize(1);

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult())
                .as("none of this advanced the process")
                .isNotNull();
    }

    /**
     * The mirror image of {@link ShippingRetryAndIncidentTest}: same outcome -
     * retries, then an incident - but the decision is the worker's. It reports
     * how many attempts remain, and the engine raises the incident when that
     * number reaches zero.
     */
    @Test
    void raisesAnIncidentWhenTheWorkerExhaustsTheRetriesItReported() {
        ProcessInstance instance = startPaidOrderAwaitingInvoice();

        LockedExternalTask firstAttempt = fetchOne("invoice-worker-1");
        assertThat(firstAttempt.getRetries())
                .as("retries are unset until a worker decides on a number")
                .isNull();

        externalTaskService.handleFailure(firstAttempt.getId(), "invoice-worker-1",
                "Invoice service unreachable", "connect timed out", 1, 0L);

        assertThat(externalTaskService.createExternalTaskQuery()
                .processInstanceId(instance.getId()).singleResult().getRetries()).isEqualTo(1);
        assertThat(incidentFor(instance))
                .as("one retry left is not yet an incident")
                .isNull();

        LockedExternalTask secondAttempt = fetchOne("invoice-worker-1");
        externalTaskService.handleFailure(secondAttempt.getId(), "invoice-worker-1",
                "Invoice service unreachable", "connect timed out", 0, 0L);

        Incident incident = incidentFor(instance);
        assertThat(incident).isNotNull();
        assertThat(incident.getIncidentType()).isEqualTo("failedExternalTask");
        assertThat(incident.getActivityId()).isEqualTo("Task_GenerateInvoice");

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult())
                .as("an exhausted external task parks the instance, it does not kill it")
                .isNotNull();
    }

    /**
     * Drives an order through payment and shipping so it comes to rest on the
     * external task. Task_NotifyShipping is asyncBefore, hence the job.
     */
    private ProcessInstance startPaidOrderAwaitingInvoice() {
        String correlationId = UUID.randomUUID().toString();

        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "order-payment-correlation", correlationId,
                Map.of("correlationId", correlationId, "customerName", "InvoiceCustomer", "amount", BigDecimal.TEN));

        runtimeService.createMessageCorrelation("PaymentReceived")
                .processInstanceVariableEquals("correlationId", correlationId)
                .correlateWithResult();

        Job shippingCall = managementService.createJobQuery()
                .processInstanceId(instance.getId())
                .singleResult();
        managementService.executeJob(shippingCall.getId());

        return instance;
    }

    private LockedExternalTask fetchOne(String workerId) {
        return externalTaskService.fetchAndLock(1, workerId)
                .topic(TOPIC, LOCK_DURATION)
                .variables("correlationId")
                .execute()
                .getFirst();
    }

    private Incident incidentFor(ProcessInstance instance) {
        return runtimeService.createIncidentQuery()
                .processInstanceId(instance.getId())
                .singleResult();
    }

    private Object historicVariable(ProcessInstance instance, String name) {
        return historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId())
                .variableName(name)
                .singleResult()
                .getValue();
    }
}
