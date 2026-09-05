package az.company.camunda.exception;

public class InvalidRequestException extends ApplicationException {

    public InvalidRequestException(String errorCode, String message) {
        super(errorCode, message);
    }
}
