package az.company.camunda.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The one test in this repository that talks to a real Keycloak. Everything else
 * about registration is covered without a container; what cannot be is whether the
 * Admin API accepts our payloads and whether the realm file we ship actually grants
 * this service the rights it needs.
 *
 * <p>Skipped, not failed, when no Docker daemon is available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class KeycloakRegistrationIntegrationTest {

    private static final String REALM = "camunda-demo";
    private static final int KEYCLOAK_PORT = 8080;
    private static final String REGISTRAR_CLIENT_ID = "camunda-demo-registrar";
    private static final String REGISTRAR_SECRET = "registrar-client-secret";
    private static final String IMPORT_PATH = "/opt/keycloak/data/import/realm-camunda-demo.json";

    // Static and shared: one Keycloak start for the whole class. Ryuk removes it
    // when the JVM exits, so it is never stopped here.
    @Container
    static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.0"))
                    .withExposedPorts(KEYCLOAK_PORT)
                    // The realm the application ships, not a copy: an import that
                    // stops working must fail this test.
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(
                                    Path.of("keycloak", "realm-camunda-demo.json").toAbsolutePath()),
                            IMPORT_PATH)
                    .withCommand("start-dev", "--import-realm")
                    // A 200 on the realm's public endpoint proves both that Keycloak
                    // is up and that the import finished.
                    .waitingFor(Wait.forHttp("/realms/" + REALM)
                            .forPort(KEYCLOAK_PORT)
                            .withStartupTimeout(Duration.ofMinutes(3)));

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("keycloak.admin.base-url", KeycloakRegistrationIntegrationTest::keycloakBaseUrl);
        // The test profile points every registration at the keycloak-test provider
        // to keep startup offline; this is the one run where that provider has to
        // resolve to something real.
        registry.add("spring.security.oauth2.client.provider.keycloak-test.token-uri",
                () -> keycloakBaseUrl() + "/realms/" + REALM + "/protocol/openid-connect/token");
    }

    @Test
    void postRegistrations_whenKeycloakIsRunning_thenTheUserExistsInTheRealm() throws Exception {
        // Given a username nobody in the realm holds
        String username = uniqueUsername();

        // When the sign-up is submitted
        MockHttpServletResponse response = register(username);

        // Then the account is in Keycloak, enabled, under the submitted details
        assertThat(response.getStatus()).isEqualTo(201);
        Map<String, Object> user = findUser(username);
        assertThat(user).containsEntry("enabled", true);
        assertThat(user).containsEntry("email", username + "@example.com");
    }

    @Test
    void postRegistrations_whenKeycloakIsRunning_thenTheUserHasTheOrderViewerRole() throws Exception {
        // Given a new account
        String username = uniqueUsername();
        register(username);

        // When its realm role mappings are read back from Keycloak
        String userId = String.valueOf(findUser(username).get("id"));
        List<Map<String, Object>> realmRoles = adminGet(
                "/admin/realms/" + REALM + "/users/" + userId + "/role-mappings/realm");

        // Then it can read orders and nothing more - the least privilege this
        // service grants on sign-up
        assertThat(realmRoles).extracting(role -> role.get("name")).contains("order-viewer");
        assertThat(realmRoles).extracting(role -> role.get("name")).doesNotContain("order-admin");
    }

    @Test
    void postRegistrations_whenUsernameIsAlreadyInTheRealm_thenConflict() throws Exception {
        // Given alice, who is imported with the realm
        // When somebody signs up as her
        MockHttpServletResponse response = register("alice");

        // Then Keycloak's refusal reaches the caller as a conflict, in our words
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("USERNAME_TAKEN");
    }

    private MockHttpServletResponse register(String username) throws Exception {
        String payload = """
                {"username":"%s","email":"%s@example.com","firstName":"Dave",
                 "lastName":"Devlin","password":"s3cret-passphrase"}"""
                .formatted(username, username);

        return mockMvc.perform(post("/api/v1/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn()
                .getResponse();
    }

    private Map<String, Object> findUser(String username) {
        List<Map<String, Object>> users =
                adminGet("/admin/realms/" + REALM + "/users?username=" + username + "&exact=true");

        assertThat(users).hasSize(1);
        return users.getFirst();
    }

    private List<Map<String, Object>> adminGet(String path) {
        return RestClient.create().get()
                .uri(keycloakBaseUrl() + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceAccountToken())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    private String serviceAccountToken() {
        Map<String, Object> token = RestClient.create().post()
                .uri(keycloakBaseUrl() + "/realms/" + REALM + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&client_id=" + REGISTRAR_CLIENT_ID
                        + "&client_secret=" + REGISTRAR_SECRET)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        return String.valueOf(token.get("access_token"));
    }

    // Isolation without cleanup: this service can create users but not delete them,
    // so each test works on a username no other run has used.
    private String uniqueUsername() {
        return "dave-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String keycloakBaseUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(KEYCLOAK_PORT);
    }
}
