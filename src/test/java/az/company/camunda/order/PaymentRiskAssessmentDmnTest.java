package az.company.camunda.order;

import org.camunda.bpm.engine.DecisionService;
import org.camunda.bpm.dmn.engine.DmnDecisionTableResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DMN decision table backing Task_AssessRisk is tested here in
 * isolation via DecisionService - no process instance required. This is
 * the standard way to unit-test business rules independently of the
 * process that consumes them.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentRiskAssessmentDmnTest {

    @Autowired
    private DecisionService decisionService;

    @Test
    void classifiesAmountIntoTheExpectedRiskLevel() {
        assertThat(riskLevelFor(50)).isEqualTo("LOW");
        assertThat(riskLevelFor(99.99)).isEqualTo("LOW");
        assertThat(riskLevelFor(100)).isEqualTo("MEDIUM");
        assertThat(riskLevelFor(999.99)).isEqualTo("MEDIUM");
        assertThat(riskLevelFor(1000)).isEqualTo("HIGH");
        assertThat(riskLevelFor(50000)).isEqualTo("HIGH");
    }

    private String riskLevelFor(double amount) {
        DmnDecisionTableResult result = decisionService.evaluateDecisionTableByKey(
                "Decision_PaymentRisk", Map.of("amount", amount));
        return (String) result.getSingleResult().getEntry("riskLevel");
    }
}
