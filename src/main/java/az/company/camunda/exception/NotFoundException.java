package az.company.camunda.exception;

/**
 * Base for "what you asked for does not exist", which the advice maps to a
 * {@code 404}. Categorising by outcome rather than by entity means a new
 * not-found case gets the right status without the advice growing a branch for
 * it.
 *
 * <p>The detail message is for the log and never for the response body; the
 * body is built from the message key so it can be localized.
 */
public abstract class NotFoundException extends RuntimeException {

    private final String errorCode;
    private final String messageKey;
    private final transient Object[] messageArgs;

    protected NotFoundException(String detail, String errorCode, String messageKey, Object... messageArgs) {
        super(detail);
        this.errorCode = errorCode;
        this.messageKey = messageKey;
        this.messageArgs = messageArgs;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Object[] getMessageArgs() {
        return messageArgs.clone();
    }
}
