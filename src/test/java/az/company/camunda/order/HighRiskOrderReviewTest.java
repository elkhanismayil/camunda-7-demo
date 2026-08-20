package az.company.camunda.order;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
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
 * Proves that Task_AssessRisk's DMN output actually drives the process
 * instead of being a decorative variable: high-value orders are routed
 * through a candidate-group user task before they ever reach the
 * payment-wait gateway, while low/medium orders skip straight past it.
 */
@SpringBootTest
@ActiveProfiles("test")
class HighRiskOrderReviewTest {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProcessEngine processEngine;

    @BeforeEach
    void initAssertions() {
        init(processEngine);
    }

    @Test
    void routesHighValueOrdersThroughManualReviewBeforeWaitingForPayment() {
        String correlationId = UUID.randomUUID().toString();

        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "order-payment-correlation", correlationId,
                Map.of("correlationId", correlationId, "customerName", "BigSpender", "amount", BigDecimal.valueOf(5000)));

        assertThat(instance).isWaitingAt("Task_ManualReview");

        Task reviewTask = taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .taskCandidateGroup("risk-review")
                .singleResult();
        assertThat(reviewTask).isNotNull();

        taskService.complete(reviewTask.getId());

        assertThat(instance).isWaitingAt("Gateway_WaitForEvent");
    }

    @Test
    void skipsManualReviewForLowValueOrders() {
        String correlationId = UUID.randomUUID().toString();

        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "order-payment-correlation", correlationId,
                Map.of("correlationId", correlationId, "customerName", "SmallBuyer", "amount", BigDecimal.valueOf(15)));

        assertThat(taskService.createTaskQuery().processInstanceId(instance.getId()).count()).isZero();
        assertThat(instance).isWaitingAt("Gateway_WaitForEvent");
    }
}
