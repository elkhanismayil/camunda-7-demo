package az.company.camunda.auth;

import az.company.camunda.exception.UpstreamTimeoutException;
import az.company.camunda.exception.UpstreamUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationApiTest {

    private static final String REGISTRATIONS_PATH = "/api/v1/registrations";
    private static final String USER_ID = "1f3c9a2e-0000-4000-8000-000000000001";
    private static final String PASSWORD = "s3cret-passphrase";
    private static final String VALID_PAYLOAD = """
            {"username":"dave","email":"dave@example.com","firstName":"Dave",
             "lastName":"Devlin","password":"%s"}""".formatted(PASSWORD);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KeycloakUserClient keycloakUserClient;

    @Test
    void postRegistrations_whenRequestIsValid_thenCreated() throws Exception {
        // Given an identity provider that accepts the user
        givenKeycloakAcceptsTheUser();

        // When an anonymous caller signs up
        MockHttpServletResponse response = register(VALID_PAYLOAD);

        // Then the new account is reported as created, with its location
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Location")).isEqualTo(REGISTRATIONS_PATH + "/" + USER_ID);
        assertThat(response.getContentAsString()).contains(USER_ID).contains("dave");
    }

    @Test
    void postRegistrations_whenRequestIsValid_thenTheResponseDoesNotEchoThePassword() throws Exception {
        // Given an identity provider that accepts the user
        givenKeycloakAcceptsTheUser();

        // When an anonymous caller signs up
        MockHttpServletResponse response = register(VALID_PAYLOAD);

        // Then the credential is not reflected back
        assertThat(response.getContentAsString()).doesNotContain(PASSWORD);
    }

    @Test
    void postRegistrations_whenCallerIsAnonymous_thenNotUnauthorized() throws Exception {
        // Given a caller with no token - somebody without an account cannot present
        // one to ask for an account
        // When they post an empty payload
        MockHttpServletResponse response = register("{}");

        // Then the endpoint judges the payload rather than refusing the caller
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void postRegistrations_whenPayloadIsInvalid_thenBadRequestNamingTheOffendingFields() throws Exception {
        // Given a payload with a malformed email and a short password
        String payload = """
                {"username":"dave","email":"not-an-email","firstName":"Dave",
                 "lastName":"Devlin","password":"short"}""";

        // When it is submitted
        MockHttpServletResponse response = register(payload);

        // Then the caller is told which fields were wrong, not just "invalid"
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("VALIDATION_FAILED").contains("email").contains("password");
    }

    @Test
    void postRegistrations_whenUsernameIsTaken_thenConflict() throws Exception {
        // Given a username somebody already holds
        when(keycloakUserClient.createUser(any())).thenThrow(new DuplicateUsernameException("dave"));

        // When the sign-up is submitted
        MockHttpServletResponse response = register(VALID_PAYLOAD);

        // Then it is a conflict, described in our own words
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("USERNAME_TAKEN").contains("dave");
    }

    @Test
    void postRegistrations_whenPasswordIsRejectedByTheRealmPolicy_thenBadRequest() throws Exception {
        // Given a password the realm policy refuses
        when(keycloakUserClient.createUser(any())).thenThrow(new RegistrationRejectedException("dave"));

        // When the sign-up is submitted
        MockHttpServletResponse response = register(VALID_PAYLOAD);

        // Then it is the caller's request that is at fault
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("REGISTRATION_REJECTED");
    }

    @Test
    void postRegistrations_whenIdentityProviderIsUnavailable_thenBadGateway() throws Exception {
        // Given a Keycloak that cannot be reached
        when(keycloakUserClient.createUser(any()))
                .thenThrow(new UpstreamUnavailableException("IDENTITY_PROVIDER_UNAVAILABLE",
                        "The identity provider could not be reached."));

        // When the sign-up is submitted
        MockHttpServletResponse response = register(VALID_PAYLOAD);

        // Then our dependency failure is not reported as the caller's mistake
        assertThat(response.getStatus()).isEqualTo(502);
    }

    @Test
    void postRegistrations_whenIdentityProviderTimesOut_thenGatewayTimeout() throws Exception {
        // Given a Keycloak that accepts the connection and then stalls
        when(keycloakUserClient.createUser(any()))
                .thenThrow(new UpstreamTimeoutException("IDENTITY_PROVIDER_TIMEOUT",
                        "The identity provider did not respond."));

        // When the sign-up is submitted
        MockHttpServletResponse response = register(VALID_PAYLOAD);

        // Then a timeout is distinguishable from a refusal in monitoring
        assertThat(response.getStatus()).isEqualTo(504);
    }

    @Test
    void postRegistrations_whenRequestFails_thenTheErrorCarriesATraceId() throws Exception {
        // Given any failure
        when(keycloakUserClient.createUser(any())).thenThrow(new DuplicateUsernameException("dave"));

        // When the sign-up is submitted
        MockHttpServletResponse response = register(VALID_PAYLOAD);

        // Then the response carries the id that makes it findable in the logs
        assertThat(response.getContentAsString()).contains("traceId");
        assertThat(response.getContentAsString()).contains(REGISTRATIONS_PATH);
    }

    @Test
    void postRegistrations_whenBodyIsNotJson_thenBadRequestWithoutParserDetail() throws Exception {
        // Given a body that is not parseable
        MockHttpServletResponse response = register("{ this is not json");

        // Then the parser's own message, which names our classes, is not echoed
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).doesNotContain("RegistrationRequest");
        assertThat(response.getContentAsString()).doesNotContain("JsonParseException");
    }

    private void givenKeycloakAcceptsTheUser() {
        when(keycloakUserClient.createUser(any())).thenReturn(USER_ID);
        when(keycloakUserClient.findRealmRole("order-viewer"))
                .thenReturn(new KeycloakUserClient.RealmRole("role-id-viewer", "order-viewer"));
    }

    private MockHttpServletResponse register(String payload) throws Exception {
        return mockMvc.perform(post(REGISTRATIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn()
                .getResponse();
    }
}
