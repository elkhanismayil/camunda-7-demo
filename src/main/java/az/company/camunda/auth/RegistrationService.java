package az.company.camunda.auth;

import org.springframework.stereotype.Service;

@Service
public class RegistrationService {

    private static final String DEFAULT_REALM_ROLE = "order-viewer";

    private final KeycloakUserClient keycloakUserClient;

    public RegistrationService(KeycloakUserClient keycloakUserClient) {
        this.keycloakUserClient = keycloakUserClient;
    }

    public RegistrationResponse register(RegistrationRequest request) {
        String userId = keycloakUserClient.createUser(request);

        // Read-only is the right default for an account nobody has vetted, and it
        // is granted here rather than as a Keycloak realm default role so the rule
        // lives in code that can be read and tested, not in a JSON file.
        KeycloakUserClient.RealmRole viewerRole = keycloakUserClient.findRealmRole(DEFAULT_REALM_ROLE);

        // A failure here is reported rather than compensated. Deleting the user we
        // just created is a fourth admin call with its own failure mode, and a
        // sign-up that returned an error is a state a person can act on - whereas a
        // "successful" sign-up with no role logs in and then returns 403 everywhere.
        keycloakUserClient.assignRealmRole(userId, viewerRole);

        return new RegistrationResponse(userId, request.username());
    }
}
