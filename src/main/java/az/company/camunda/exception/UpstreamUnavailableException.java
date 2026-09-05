package az.company.camunda.exception;

public class UpstreamUnavailableException extends ApplicationException {

    public UpstreamUnavailableException(String errorCode, String message) {
        super(errorCode, message);
    }

    public UpstreamUnavailableException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
