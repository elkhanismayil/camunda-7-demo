package az.company.camunda.auth;

import az.company.camunda.exception.InvalidRequestException;

public class RegistrationRejectedException extends InvalidRequestException {

    public RegistrationRejectedException(String username) {
        super("REGISTRATION_REJECTED",
                "Registration for username '" + username + "' was rejected. "
                        + "The password does not meet the password policy, or the details are not acceptable.");
    }
}
