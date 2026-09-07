package az.company.camunda.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationUiTest {

    private static final String USER_ID = "1f3c9a2e-0000-4000-8000-000000000001";
    private static final String PASSWORD = "s3cret-passphrase";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KeycloakUserClient keycloakUserClient;

    @Test
    void getRegister_whenUserIsAnonymous_thenServeTheForm() throws Exception {
        // Given a visitor with no account
        // When they open the sign-up page
        MvcResult result = mockMvc.perform(get("/register")).andReturn();

        // Then it is served rather than redirected into the login they cannot pass
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("action=\"/register\"");
    }

    @Test
    void getRegister_whenUserIsAnonymous_thenTheFormCarriesACsrfToken() throws Exception {
        // Given the UI chain, where CSRF protection stays on
        // When the sign-up page renders
        MvcResult result = mockMvc.perform(get("/register")).andReturn();

        // Then the form ships the token, or every submission would be refused
        assertThat(result.getResponse().getContentAsString()).contains("_csrf");
    }

    @Test
    void postRegister_whenRequestIsValid_thenRedirectToOrders() throws Exception {
        // Given an identity provider that accepts the user
        givenKeycloakAcceptsTheUser();

        // When the form is submitted
        MvcResult result = mockMvc.perform(validForm()).andReturn();

        // Then the browser is sent on to the orders page, which triggers the login
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/orders");
    }

    @Test
    void postRegister_whenRequestIsValid_thenCarryAFlashMessage() throws Exception {
        // Given an identity provider that accepts the user
        givenKeycloakAcceptsTheUser();

        // When the form is submitted
        MvcResult result = mockMvc.perform(validForm()).andReturn();

        // Then the next page can tell them the account exists and to sign in
        assertThat(result.getFlashMap().get("message")).asString().contains("dave");
    }

    @Test
    void postRegister_whenPayloadIsInvalid_thenRedisplayTheFormWithTheErrors() throws Exception {
        // Given a malformed email
        MvcResult result = mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", "dave")
                        .param("email", "not-an-email")
                        .param("firstName", "Dave")
                        .param("lastName", "Devlin")
                        .param("password", PASSWORD))
                .andReturn();

        // Then the form comes back naming the field that was wrong
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("email");
    }

    @Test
    void postRegister_whenPayloadIsInvalid_thenKeepTheSubmittedUsername() throws Exception {
        // Given a submission that fails on one field only
        MvcResult result = mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", "dave")
                        .param("email", "not-an-email")
                        .param("firstName", "Dave")
                        .param("lastName", "Devlin")
                        .param("password", PASSWORD))
                .andReturn();

        // Then the fields that were fine are not cleared
        assertThat(result.getResponse().getContentAsString()).contains("value=\"dave\"");
    }

    @Test
    void postRegister_whenPayloadIsInvalid_thenDoNotEchoThePasswordIntoThePage() throws Exception {
        // Given a submission that comes back to the browser
        MvcResult result = mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", "dave")
                        .param("email", "not-an-email")
                        .param("firstName", "Dave")
                        .param("lastName", "Devlin")
                        .param("password", PASSWORD))
                .andReturn();

        // Then the credential is not written into the HTML, where it would land in
        // the browser cache and in any page the user saves or screenshots
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PASSWORD);
    }

    @Test
    void postRegister_whenUsernameIsTaken_thenRedisplayTheFormWithTheReason() throws Exception {
        // Given a username somebody already holds
        when(keycloakUserClient.createUser(any())).thenThrow(new DuplicateUsernameException("dave"));

        // When the form is submitted
        MvcResult result = mockMvc.perform(validForm()).andReturn();

        // Then the visitor is told what to change, on the page they were on
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("already taken");
    }

    @Test
    void postRegister_whenCsrfTokenIsMissing_thenForbidden() throws Exception {
        // Given a submission from another origin
        MvcResult result = mockMvc.perform(post("/register")
                        .param("username", "dave")
                        .param("email", "dave@example.com")
                        .param("firstName", "Dave")
                        .param("lastName", "Devlin")
                        .param("password", PASSWORD))
                .andReturn();

        // Then it is refused, like every other form on this chain
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    private void givenKeycloakAcceptsTheUser() {
        when(keycloakUserClient.createUser(any())).thenReturn(USER_ID);
        when(keycloakUserClient.findRealmRole("order-viewer"))
                .thenReturn(new KeycloakUserClient.RealmRole("role-id-viewer", "order-viewer"));
    }

    private MockHttpServletRequestBuilder validForm() {
        return post("/register")
                .with(csrf())
                .param("username", "dave")
                .param("email", "dave@example.com")
                .param("firstName", "Dave")
                .param("lastName", "Devlin")
                .param("password", PASSWORD);
    }
}
