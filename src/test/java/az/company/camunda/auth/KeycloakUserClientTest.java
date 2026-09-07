package az.company.camunda.auth;

import az.company.camunda.exception.UpstreamTimeoutException;
import az.company.camunda.exception.UpstreamUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KeycloakUserClientTest {

    private static final String BASE_URL = "http://keycloak.test";
    private static final String REALM = "camunda-demo";
    private static final String USERS_URL = BASE_URL + "/admin/realms/" + REALM + "/users";
    private static final String USER_ID = "1f3c9a2e-0000-4000-8000-000000000001";
    private static final String PASSWORD = "s3cret-passphrase";

    private MockRestServiceServer keycloak;
    private KeycloakUserClient keycloakUserClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        keycloak = MockRestServiceServer.bindTo(builder).build();
        keycloakUserClient = new KeycloakUserClient(builder.build(), REALM);
    }

    @Test
    void createUser_whenKeycloakAcceptsTheUser_thenReturnTheIdFromTheLocationHeader() {
        // Given Keycloak answering 201 with the new user's URL, which is the only
        // place the generated id appears - there is no id in the response body
        keycloak.expect(requestTo(USERS_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withCreatedEntity(URI.create(USERS_URL + "/" + USER_ID)));

        // When the user is created
        String actualUserId = keycloakUserClient.createUser(request());

        // Then the id is taken from that URL
        assertThat(actualUserId).isEqualTo(USER_ID);
        keycloak.verify();
    }

    @Test
    void createUser_whenKeycloakAcceptsTheUser_thenSendAnEnabledUserWithAPermanentPassword() {
        // Given Keycloak answering 201
        keycloak.expect(requestTo(USERS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.username").value("dave"))
                .andExpect(jsonPath("$.email").value("dave@example.com"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.credentials[0].type").value("password"))
                .andExpect(jsonPath("$.credentials[0].value").value(PASSWORD))
                // Temporary credentials force a password reset the demo has no page for.
                .andExpect(jsonPath("$.credentials[0].temporary").value(false))
                .andRespond(withCreatedEntity(URI.create(USERS_URL + "/" + USER_ID)));

        // When the user is created
        keycloakUserClient.createUser(request());

        // Then the payload is the one asserted above
        keycloak.verify();
    }

    @Test
    void createUser_whenUsernameIsTaken_thenThrowDuplicateUsername() {
        // Given Keycloak refusing a username somebody already holds
        keycloak.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errorMessage\":\"User exists with same username\"}"));

        // When the user is created
        // Then the fact is passed through, but not Keycloak's wording
        assertThatThrownBy(() -> keycloakUserClient.createUser(request()))
                .isInstanceOf(DuplicateUsernameException.class)
                .hasMessageContaining("dave")
                .hasMessageNotContaining("User exists with same username");
    }

    @Test
    void createUser_whenPasswordViolatesTheRealmPolicy_thenThrowRegistrationRejected() {
        // Given Keycloak rejecting the credential on its own policy
        keycloak.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalidPasswordMinLengthMessage\"}"));

        // When the user is created
        // Then it is a client error, described in our own words
        assertThatThrownBy(() -> keycloakUserClient.createUser(request()))
                .isInstanceOf(RegistrationRejectedException.class)
                .hasMessageNotContaining("invalidPasswordMinLengthMessage");
    }

    @Test
    void createUser_whenKeycloakFails_thenThrowUpstreamUnavailable() {
        // Given a Keycloak that is answering, badly
        keycloak.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("java.lang.NullPointerException at org.keycloak.services"));

        // When the user is created
        // Then it is reported as a dependency failure, with no upstream detail
        assertThatThrownBy(() -> keycloakUserClient.createUser(request()))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessageNotContaining("NullPointerException");
    }

    @Test
    void createUser_whenKeycloakIsUnreachable_thenThrowUpstreamUnavailable() {
        // Given nothing listening on the admin port
        keycloak.expect(requestTo(USERS_URL)).andRespond(withException(new ConnectException("connection refused")));

        // When the user is created
        // Then the transport failure is a dependency failure too
        assertThatThrownBy(() -> keycloakUserClient.createUser(request()))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void createUser_whenKeycloakDoesNotAnswerInTime_thenThrowUpstreamTimeout() {
        // Given a Keycloak that accepts the connection and then stalls
        keycloak.expect(requestTo(USERS_URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        // When the user is created
        // Then a timeout is distinguishable from a refusal, because the two need
        // different operational responses
        assertThatThrownBy(() -> keycloakUserClient.createUser(request()))
                .isInstanceOf(UpstreamTimeoutException.class);
    }

    @Test
    void findRealmRole_whenRoleExists_thenReturnItsIdAndName() {
        // Given the realm role this service grants on sign-up
        keycloak.expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/roles/order-viewer"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"role-id-viewer","name":"order-viewer","composite":false,
                         "clientRole":false,"containerId":"camunda-demo"}""", MediaType.APPLICATION_JSON));

        // When the role is looked up
        KeycloakUserClient.RealmRole actualRole = keycloakUserClient.findRealmRole("order-viewer");

        // Then the parts needed to assign it come back, and the fields we do not
        // model do not break the parse
        assertThat(actualRole.id()).isEqualTo("role-id-viewer");
        assertThat(actualRole.name()).isEqualTo("order-viewer");
    }

    @Test
    void findRealmRole_whenRoleIsMissingFromTheRealm_thenThrowUpstreamUnavailable() {
        // Given a realm that was never imported with the role
        keycloak.expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/roles/order-viewer"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        // When the role is looked up
        // Then it is our misconfiguration, not the caller's mistake, so it must not
        // be reported to them as a 4xx
        assertThatThrownBy(() -> keycloakUserClient.findRealmRole("order-viewer"))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void assignRealmRole_whenKeycloakAcceptsTheMapping_thenPostTheRoleToTheUser() {
        // Given the realm role mapping endpoint for the new user
        keycloak.expect(requestTo(USERS_URL + "/" + USER_ID + "/role-mappings/realm"))
                .andExpect(method(HttpMethod.POST))
                // An array, because the endpoint assigns a set of roles at once.
                .andExpect(jsonPath("$[0].id").value("role-id-viewer"))
                .andExpect(jsonPath("$[0].name").value("order-viewer"))
                .andRespond(withNoContent());

        // When the role is assigned
        keycloakUserClient.assignRealmRole(USER_ID, new KeycloakUserClient.RealmRole("role-id-viewer", "order-viewer"));

        // Then the mapping was posted as asserted above
        keycloak.verify();
    }

    @Test
    void assignRealmRole_whenKeycloakFails_thenThrowUpstreamUnavailable() {
        // Given a Keycloak that cannot store the mapping
        keycloak.expect(requestTo(USERS_URL + "/" + USER_ID + "/role-mappings/realm"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // When the role is assigned
        // Then the caller is not told the sign-up succeeded
        assertThatThrownBy(() -> keycloakUserClient.assignRealmRole(USER_ID,
                new KeycloakUserClient.RealmRole("role-id-viewer", "order-viewer")))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    private RegistrationRequest request() {
        return new RegistrationRequest("dave", "dave@example.com", "Dave", "Devlin", PASSWORD);
    }
}
