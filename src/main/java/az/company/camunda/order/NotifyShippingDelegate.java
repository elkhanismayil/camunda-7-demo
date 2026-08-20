package az.company.camunda.order;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Simulates calling an external shipping provider. Demo-only failure
 * trigger: a customer name containing "ShipFail" makes the provider
 * reject the order, so the saga's compensation path can be exercised
 * without needing a real external dependency.
 */
@Component("notifyShippingDelegate")
public class NotifyShippingDelegate implements JavaDelegate {

    static final String SHIPPING_FAILED_ERROR_CODE = "SHIPPING_FAILED";

    @Override
    public void execute(DelegateExecution execution) {
        String customerName = (String) execution.getVariable("customerName");
        if (customerName != null && customerName.contains("ShipFail")) {
            throw new BpmnError(SHIPPING_FAILED_ERROR_CODE,
                    "Shipping provider rejected the order for " + customerName);
        }
    }
}
