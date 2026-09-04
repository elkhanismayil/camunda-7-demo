package az.company.camunda.exception;

/**
 * Thrown when no live order answers to a correlation id - whether it never
 * existed, was soft-deleted, or its process instance is no longer waiting. The
 * correlation id travels with the exception so both the log line and the
 * response name the identifier that failed.
 */
public class OrderNotFoundException extends NotFoundException {

    private static final String ERROR_CODE = "ORDER_NOT_FOUND";
    private static final String MESSAGE_KEY = "error.order.notFound";

    public OrderNotFoundException(String correlationId) {
        super("No order found for correlationId=" + correlationId, ERROR_CODE, MESSAGE_KEY, correlationId);
    }
}
