package az.company.camunda.exception;

public class UpstreamTimeoutException extends ApplicationException {

    public UpstreamTimeoutException(String errorCode, String message) {
        super(errorCode, message);
    }

    public UpstreamTimeoutException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
