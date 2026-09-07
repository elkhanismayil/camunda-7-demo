package az.company.camunda.auth;

import az.company.camunda.exception.UpstreamTimeoutException;
import az.company.camunda.exception.UpstreamUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

public class KeycloakUserClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakUserClient.class);

    private static final String UNAVAILABLE_CODE = "IDENTITY_PROVIDER_UNAVAILABLE";
    private static final String TIMEOUT_CODE = "IDENTITY_PROVIDER_TIMEOUT";
    private static final String PASSWORD_CREDENTIAL_TYPE = "password";

    private final RestClient restClient;
    private final String realm;

    public KeycloakUserClient(RestClient keycloakAdminRestClient, String realm) {
        this.restClient = keycloakAdminRestClient;
        this.realm = realm;
    }

    public String createUser(RegistrationRequest request) {
        UserRepresentation user = new UserRepresentation(
                request.username(),
                request.email(),
                request.firstName(),
                request.lastName(),
                true,
                List.of(new CredentialRepresentation(PASSWORD_CREDENTIAL_TYPE, request.password(), false)));

        String description = "registering username '" + request.username() + "'";

        ResponseEntity<Void> response = call(description, () -> restClient.post()
                .uri("/admin/realms/{realm}/users", realm)
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        (ignoredRequest, errorResponse) -> {
                            throw creationFailure(errorResponse, request.username(), description);
                        })
                .toBodilessEntity());

        return userIdFrom(response, description);
    }

    public RealmRole findRealmRole(String roleName) {
        String description = "looking up realm role '" + roleName + "'";

        return call(description, () -> restClient.get()
                .uri("/admin/realms/{realm}/roles/{roleName}", realm, roleName)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        (ignoredRequest, errorResponse) -> {
                            throw unavailable(errorResponse, description);
                        })
                .body(RealmRole.class));
    }

    public void assignRealmRole(String userId, RealmRole role) {
        String description = "granting realm role '" + role.name() + "'";

        call(description, () -> restClient.post()
                .uri("/admin/realms/{realm}/users/{userId}/role-mappings/realm", realm, userId)
                .contentType(MediaType.APPLICATION_JSON)
                // The endpoint assigns a set of roles in one call, so even a single
                // role goes in an array.
                .body(List.of(role))
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        (ignoredRequest, errorResponse) -> {
                            throw unavailable(errorResponse, description);
                        })
                .toBodilessEntity());
    }

    private <T> T call(String description, Supplier<T> keycloakCall) {
        try {
            return keycloakCall.get();
        } catch (ResourceAccessException exception) {
            if (causedByTimeout(exception)) {
                log.error("Timed out while {}", description, exception);
                throw new UpstreamTimeoutException(TIMEOUT_CODE,
                        "The identity provider did not respond while " + description + ".", exception);
            }
            log.error("Could not reach the identity provider while {}", description, exception);
            throw new UpstreamUnavailableException(UNAVAILABLE_CODE,
                    "The identity provider could not be reached while " + description + ".", exception);
        }
    }

    private RuntimeException creationFailure(ClientHttpResponse response, String username, String description)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();

        if (status.isSameCodeAs(HttpStatus.CONFLICT)) {
            log.warn("Keycloak refused a duplicate registration for username '{}': {}", username, readBody(response));
            return new DuplicateUsernameException(username);
        }

        // Only a 400 is about what the caller sent. A 401 or 403 means our own
        // service account lost its manage-users grant, which is our fault and must
        // not be reported to the caller as a bad request.
        if (status.isSameCodeAs(HttpStatus.BAD_REQUEST)) {
            log.warn("Keycloak rejected the registration for username '{}': {}", username, readBody(response));
            return new RegistrationRejectedException(username);
        }

        return unavailable(response, description);
    }

    private RuntimeException unavailable(ClientHttpResponse response, String description) throws IOException {
        log.error("Keycloak answered {} while {}: {}", response.getStatusCode(), description, readBody(response));
        return new UpstreamUnavailableException(UNAVAILABLE_CODE,
                "The identity provider failed while " + description + ".");
    }

    private String userIdFrom(ResponseEntity<Void> response, String description) {
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            log.error("Keycloak answered {} with no Location header while {}", response.getStatusCode(), description);
            throw new UpstreamUnavailableException(UNAVAILABLE_CODE,
                    "The identity provider failed while " + description + ".");
        }

        // The generated id appears only in the Location URL - the 201 has no body.
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String readBody(ClientHttpResponse response) throws IOException {
        return StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
    }

    private boolean causedByTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RealmRole(String id, String name) {
    }

    record UserRepresentation(String username,
                              String email,
                              String firstName,
                              String lastName,
                              boolean enabled,
                              List<CredentialRepresentation> credentials) {
    }

    record CredentialRepresentation(String type, String value, boolean temporary) {
    }
}
