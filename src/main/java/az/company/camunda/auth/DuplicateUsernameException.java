package az.company.camunda.auth;

import az.company.camunda.exception.ConflictException;

public class DuplicateUsernameException extends ConflictException {

    public DuplicateUsernameException(String username) {
        super("USERNAME_TAKEN", "Username '" + username + "' is already taken.");
    }
}
