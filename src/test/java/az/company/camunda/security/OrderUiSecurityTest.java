package az.company.camunda.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderUiSecurityTest {

    private static final String LOGIN_ENTRY_POINT = "/oauth2/authorization/keycloak";
    private static final String KEYCLOAK_AUTHORIZATION_ENDPOINT =
            "http://localhost:8083/realms/camunda-demo/protocol/openid-connect/auth";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Test
    void getOrders_whenUserIsAnonymous_thenRedirectToTheLoginEntryPoint() throws Exception {
        // Given an anonymous browser
        // When it asks for the order list
        MockHttpServletResponse response = mockMvc.perform(get("/orders")).andReturn().getResponse();

        // Then it is sent to the OAuth2 entry point rather than served the page
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(LOGIN_ENTRY_POINT);
    }

    @Test
    void getAuthorizationEndpoint_whenUserIsAnonymous_thenRedirectToKeycloak() throws Exception {
        // Given the entry point the previous test lands on
        // When the browser follows it
        MockHttpServletResponse response = mockMvc.perform(get("/oauth2/authorization/keycloak"))
                .andReturn()
                .getResponse();

        // Then the second hop leaves this application for Keycloak's own login page
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .startsWith(KEYCLOAK_AUTHORIZATION_ENDPOINT)
                .contains("response_type=code")
                .contains("client_id=camunda-demo-api")
                .contains("scope=openid");
    }

    @Test
    void getOrders_whenUserHasViewerRole_thenOk() throws Exception {
        // Given bob, who may read
        // When he asks for the order list
        MockHttpServletResponse response = mockMvc.perform(get("/orders").with(signedInAs("bob", "order-viewer")))
                .andReturn()
                .getResponse();

        // Then the page renders
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void getOrders_whenUserHasViewerRole_thenWriteControlsAreNotRendered() throws Exception {
        // Given bob, who may read but not write
        // When he opens the order list
        MockHttpServletResponse response = mockMvc.perform(get("/orders").with(signedInAs("bob", "order-viewer")))
                .andReturn()
                .getResponse();

        // Then he is not shown a button whose only possible outcome is a 403
        assertThat(response.getContentAsString()).doesNotContain("Create Order");
    }

    @Test
    void getOrders_whenUserHasAdminRole_thenWriteControlsAreRendered() throws Exception {
        // Given alice, who may write
        // When she opens the order list
        MockHttpServletResponse response = mockMvc.perform(get("/orders").with(signedInAs("alice", "order-admin")))
                .andReturn()
                .getResponse();

        // Then the controls she is allowed to use are there
        assertThat(response.getContentAsString()).contains("Create Order");
    }

    @Test
    void getOrders_whenUserIsAuthenticated_thenPageOffersLogout() throws Exception {
        // Given any signed-in user
        // When the order list renders
        MockHttpServletResponse response = mockMvc.perform(get("/orders").with(signedInAs("bob", "order-viewer")))
                .andReturn()
                .getResponse();

        // Then there is a way out that is not "clear your cookies"
        assertThat(response.getContentAsString()).contains("/logout");
    }

    @Test
    void getOrders_whenUserIsAuthenticated_thenPageShowsTheUsername() throws Exception {
        // Given a signed-in user whose identity the page should surface
        // When the order list renders
        MockHttpServletResponse response = mockMvc.perform(get("/orders").with(signedInAs("bob", "order-viewer")))
                .andReturn()
                .getResponse();

        // Then who you are signed in as is visible without opening a debugger
        assertThat(response.getContentAsString()).contains("bob");
    }

    @Test
    void getOrders_whenUserHasNoRealmRoles_thenForbidden() throws Exception {
        // Given carol, authenticated but granted nothing
        // When she asks for the order list
        MockHttpServletResponse response = mockMvc.perform(get("/orders").with(signedInAs("carol")))
                .andReturn()
                .getResponse();

        // Then it is a 403, not a redirect back to login - we know who she is
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void postOrders_whenUserHasViewerRole_thenForbidden() throws Exception {
        // Given bob, who may only read
        // When he submits the create form
        MockHttpServletResponse response = mockMvc.perform(post("/orders")
                        .with(signedInAs("bob", "order-viewer"))
                        .with(csrf())
                        .param("customerName", "ViewerAttempt")
                        .param("amount", "10"))
                .andReturn()
                .getResponse();

        // Then creating an order is refused
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void postOrders_whenUserHasAdminRole_thenRedirectBackToTheList() throws Exception {
        // Given alice, who may write
        // When she submits the create form
        MockHttpServletResponse response = mockMvc.perform(post("/orders")
                        .with(signedInAs("alice", "order-admin"))
                        .with(csrf())
                        .param("customerName", "AdminCreated")
                        .param("amount", "25"))
                .andReturn()
                .getResponse();

        // Then the order is created and the browser is sent back to the list
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/orders");
    }

    @Test
    void postOrderPayment_whenUserHasViewerRole_thenForbidden() throws Exception {
        // Given bob, who may only read
        // When he tries to report a payment
        MockHttpServletResponse response = mockMvc.perform(post("/orders/any-correlation-id/payment")
                        .with(signedInAs("bob", "order-viewer"))
                        .with(csrf()))
                .andReturn()
                .getResponse();

        // Then advancing the process is refused
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void postOrderDelete_whenUserHasViewerRole_thenForbidden() throws Exception {
        // Given bob, who may only read
        // When he tries to delete an order
        MockHttpServletResponse response = mockMvc.perform(post("/orders/any-correlation-id/delete")
                        .with(signedInAs("bob", "order-viewer"))
                        .with(csrf()))
                .andReturn()
                .getResponse();

        // Then reading an order and destroying it stay separate privileges
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void postOrders_whenCsrfTokenIsMissing_thenForbidden() throws Exception {
        // Given alice, who is otherwise allowed to create orders
        // When the form arrives without a CSRF token, as it would from another origin
        MockHttpServletResponse response = mockMvc.perform(post("/orders")
                        .with(signedInAs("alice", "order-admin"))
                        .param("customerName", "ForgedRequest")
                        .param("amount", "99"))
                .andReturn()
                .getResponse();

        // Then the session cookie alone is not enough to act on her behalf
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void getRoot_whenUserIsAnonymous_thenPermittedWithoutLogin() throws Exception {
        // Given an anonymous browser
        // When it opens the landing page
        MockHttpServletResponse response = mockMvc.perform(get("/")).andReturn().getResponse();

        // Then it reaches the home controller instead of the login entry point
        assertThat(response.getRedirectedUrl()).isEqualTo("/orders");
    }

    @Test
    void logout_whenUserIsAuthenticated_thenSessionIsClearedAndBrowserRedirected() throws Exception {
        // Given a logged-in user
        // When the logout form is submitted
        MockHttpServletResponse response = mockMvc.perform(post("/logout")
                        .with(signedInAs("alice", "order-admin"))
                        .with(csrf()))
                .andReturn()
                .getResponse();

        // Then logout is accepted and the browser is redirected onward.
        // Which URL that is depends on Keycloak's discovery metadata, which this
        // profile deliberately does not fetch - see application-test.yaml.
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isNotNull();
    }

    /**
     * Authorities are supplied directly rather than derived from the ID token,
     * because this post processor builds the authentication itself and never runs
     * the application's mapper - that mapping is covered by
     * {@link KeycloakOidcRealmRoleMapperTest}. Passing no role is carol's case:
     * authenticated, granted nothing.
     */
    private RequestPostProcessor signedInAs(String username, String... realmRoles) {
        SimpleGrantedAuthority[] authorities = Arrays.stream(realmRoles)
                .map(realmRole -> new SimpleGrantedAuthority("ROLE_" + realmRole))
                .toArray(SimpleGrantedAuthority[]::new);

        return oidcLogin()
                .clientRegistration(clientRegistrationRepository.findByRegistrationId("keycloak"))
                .idToken(idToken -> idToken.subject(username))
                .authorities(authorities);
    }
}
