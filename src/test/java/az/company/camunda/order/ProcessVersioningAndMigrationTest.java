package az.company.camunda.order;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.migration.MigratingProcessInstanceValidationException;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deploying a changed model never rewrites history: Camunda keys deployments
 * by process definition key and stores each one as a new <em>version</em>.
 * Instances that are already running stay pinned to the definition they were
 * started on, so a deployment can never corrupt work in flight - but it also
 * means old instances keep executing the old model until somebody explicitly
 * moves them. That move is process instance migration.
 *
 * <p>The models here are built with the BPMN model API rather than checked in
 * as .bpmn files on purpose: Spring Boot auto-deploys everything matching
 * {@code classpath*:}<!-- -->{@code **}{@code /*.bpmn} at startup, which would
 * deploy all three versions before the first assertion could observe the
 * difference between them.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProcessVersioningAndMigrationTest {

    private static final String PROCESS_KEY = "order-fulfillment-migration-demo";

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    private final List<String> deploymentIds = new ArrayList<>();

    @AfterEach
    void removeDeployments() {
        // Cascade so the running instances started here go with them; the
        // Spring context (and its H2 schema) is shared across test classes.
        deploymentIds.forEach(id -> repositoryService.deleteDeployment(id, true));
        deploymentIds.clear();
    }

    /**
     * The core guarantee: a new deployment is additive. Instances started
     * before it keep running the model they were started on, and only newly
     * started instances pick up the change.
     */
    @Test
    void leavesRunningInstancesOnTheVersionTheyWereStartedOn() {
        ProcessDefinition v1 = deploy(withoutFraudCheck());
        ProcessInstance startedOnV1 = runtimeService.startProcessInstanceByKey(PROCESS_KEY);

        ProcessDefinition v2 = deploy(withFraudCheck());

        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v2.getVersion()).isEqualTo(2);

        assertThat(definitionIdOf(startedOnV1))
                .as("an already running instance must not be dragged onto the new model")
                .isEqualTo(v1.getId());

        // startProcessInstanceByKey always resolves to the latest version.
        ProcessInstance startedOnV2 = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
        assertThat(startedOnV2.getProcessDefinitionId()).isEqualTo(v2.getId());

        // Same next click, two different outcomes - which is exactly the
        // operational problem migration exists to solve.
        completeCurrentTask(startedOnV1);
        completeCurrentTask(startedOnV2);

        assertThat(currentTaskKey(startedOnV1)).isEqualTo("Task_ShipOrder");
        assertThat(currentTaskKey(startedOnV2)).isEqualTo("Task_FraudCheck");
    }

    /**
     * mapEqualActivities() is the common case: the ids that exist in both
     * models are mapped one to one, and the instance resumes at the same
     * wait state - but now under the new definition, so the rest of its path
     * follows the new model.
     */
    @Test
    void migratesRunningInstancesOntoTheNewVersion() {
        ProcessDefinition v1 = deploy(withoutFraudCheck());
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
        ProcessDefinition v2 = deploy(withFraudCheck());

        MigrationPlan plan = runtimeService.createMigrationPlan(v1.getId(), v2.getId())
                .mapEqualActivities()
                .build();

        runtimeService.newMigration(plan)
                .processInstanceIds(instance.getId())
                .execute();

        assertThat(definitionIdOf(instance)).isEqualTo(v2.getId());
        assertThat(currentTaskKey(instance))
                .as("migration relocates the instance, it does not restart or advance it")
                .isEqualTo("Task_ReserveStock");

        completeCurrentTask(instance);

        assertThat(currentTaskKey(instance))
                .as("from here on the instance follows the new model")
                .isEqualTo("Task_FraudCheck");
    }

    /**
     * mapEqualActivities() only maps activities whose ids match. If the new
     * model renamed the very activity an instance is sitting on, the plan
     * builds fine but execution refuses it - the engine will not guess where
     * that token belongs. The fix is an explicit instruction, not a retry.
     */
    @Test
    void refusesToGuessWhereARenamedActivityWent() {
        ProcessDefinition v1 = deploy(withoutFraudCheck());
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(PROCESS_KEY);
        completeCurrentTask(instance);
        assertThat(currentTaskKey(instance)).isEqualTo("Task_ShipOrder");

        ProcessDefinition v2 = deploy(withShipOrderRenamedToDispatch());

        MigrationPlan naivePlan = runtimeService.createMigrationPlan(v1.getId(), v2.getId())
                .mapEqualActivities()
                .build();

        assertThatThrownBy(() -> runtimeService.newMigration(naivePlan)
                .processInstanceIds(instance.getId())
                .execute())
                .as("the instance is active at an activity that has no counterpart in the target")
                .isInstanceOf(MigratingProcessInstanceValidationException.class);

        assertThat(definitionIdOf(instance))
                .as("a rejected migration must leave the instance exactly as it was")
                .isEqualTo(v1.getId());

        MigrationPlan explicitPlan = runtimeService.createMigrationPlan(v1.getId(), v2.getId())
                .mapEqualActivities()
                .mapActivities("Task_ShipOrder", "Task_DispatchOrder")
                .build();

        runtimeService.newMigration(explicitPlan)
                .processInstanceIds(instance.getId())
                .execute();

        assertThat(definitionIdOf(instance)).isEqualTo(v2.getId());
        assertThat(currentTaskKey(instance)).isEqualTo("Task_DispatchOrder");
    }

    private BpmnModelInstance withoutFraudCheck() {
        return Bpmn.createExecutableProcess(PROCESS_KEY)
                .name("Order Fulfillment")
                .camundaHistoryTimeToLive(180)
                .startEvent("Event_Started")
                .userTask("Task_ReserveStock").name("Reserve Stock")
                .userTask("Task_ShipOrder").name("Ship Order")
                .endEvent("Event_Fulfilled")
                .done();
    }

    /** v2: a new step is inserted between the two existing ones. */
    private BpmnModelInstance withFraudCheck() {
        return Bpmn.createExecutableProcess(PROCESS_KEY)
                .name("Order Fulfillment")
                .camundaHistoryTimeToLive(180)
                .startEvent("Event_Started")
                .userTask("Task_ReserveStock").name("Reserve Stock")
                .userTask("Task_FraudCheck").name("Fraud Check")
                .userTask("Task_ShipOrder").name("Ship Order")
                .endEvent("Event_Fulfilled")
                .done();
    }

    /** v2': same shape as v1, but an activity id changed. */
    private BpmnModelInstance withShipOrderRenamedToDispatch() {
        return Bpmn.createExecutableProcess(PROCESS_KEY)
                .name("Order Fulfillment")
                .camundaHistoryTimeToLive(180)
                .startEvent("Event_Started")
                .userTask("Task_ReserveStock").name("Reserve Stock")
                .userTask("Task_DispatchOrder").name("Dispatch Order")
                .endEvent("Event_Fulfilled")
                .done();
    }

    private ProcessDefinition deploy(BpmnModelInstance model) {
        Deployment deployment = repositoryService.createDeployment()
                .addModelInstance("order-fulfillment.bpmn", model)
                .deploy();
        deploymentIds.add(deployment.getId());

        return repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .singleResult();
    }

    private String definitionIdOf(ProcessInstance instance) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId())
                .singleResult()
                .getProcessDefinitionId();
    }

    private Task currentTask(ProcessInstance instance) {
        return taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult();
    }

    private String currentTaskKey(ProcessInstance instance) {
        return currentTask(instance).getTaskDefinitionKey();
    }

    private void completeCurrentTask(ProcessInstance instance) {
        taskService.complete(currentTask(instance).getId());
    }
}
