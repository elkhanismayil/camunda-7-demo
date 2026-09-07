package az.company.camunda.exception;

public class ConflictException extends ApplicationException {

    public ConflictException(String errorCode, String message) {
        super(errorCode, message);
    }
}
