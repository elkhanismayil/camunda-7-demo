package az.company.camunda.order;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Simulates calling an external shipping provider, and deliberately shows
 * the two failure kinds a delegate must distinguish:
 *
 * <ul>
 *   <li><b>Business failure</b> (customer name contains "ShipFail") - the
 *       provider answered, and the answer was "no". Thrown as a
 *       {@link BpmnError} so the modelled boundary error event handles it
 *       and compensation runs. Retrying would be pointless.</li>
 *   <li><b>Technical failure</b> (customer name contains "TechFail") - the
 *       call itself broke (timeout, connection reset). Thrown as a plain
 *       runtime exception so the job executor retries it according to
 *       failedJobRetryTimeCycle, and raises an incident once retries are
 *       exhausted.</li>
 * </ul>
 *
 * The task is marked asyncBefore, so the payment recorded by the previous
 * step is already committed before this external call is attempted.
 */
@Component("notifyShippingDelegate")
public class NotifyShippingDelegate implements JavaDelegate {

    static final String SHIPPING_FAILED_ERROR_CODE = "SHIPPING_FAILED";

    @Override
    public void execute(DelegateExecution execution) {
        String customerName = (String) execution.getVariable("customerName");
        if (customerName == null) {
            return;
        }
        if (customerName.contains("ShipFail")) {
            throw new BpmnError(SHIPPING_FAILED_ERROR_CODE,
                    "Shipping provider rejected the order for " + customerName);
        }
        if (customerName.contains("TechFail")) {
            throw new IllegalStateException(
                    "Shipping provider unreachable for " + customerName);
        }
    }
}
