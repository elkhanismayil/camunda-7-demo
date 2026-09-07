package az.company.camunda.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization rules for the API, driven with pre-authenticated tokens so no
 * Keycloak is needed. The distinction the tests keep making is the one that
 * matters operationally: <b>401 means we do not know who you are, 403 means we
 * do and you may not.</b> Collapsing them hides misconfigured roles behind what
 * looks like a login problem.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderApiSecurityTest {

    private static final String CREATE_ORDER_BODY = """
            {"customerName":"SecuredCustomer","amount":42}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsAnUnauthenticatedCallWith401() throws Exception {
        mockMvc.perform(get("/api/orders/does-not-matter"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_ORDER_BODY))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/orders/does-not-matter"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnAuthenticatedCallerWithoutTheRequiredRoleWith403() throws Exception {
        mockMvc.perform(get("/api/orders/does-not-matter").with(tokenFor()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/orders")
                        .with(tokenFor("order-viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_ORDER_BODY))
                .andExpect(status().isForbidden());

        // Reading an order and destroying it are not the same privilege.
        mockMvc.perform(delete("/api/orders/does-not-matter").with(tokenFor("order-viewer")))
                .andExpect(status().isForbidden());
    }

    @Test
    void letsAViewerReadButNotWrite() throws Exception {
        // 404 rather than 403: authorization passed and the handler ran.
        mockMvc.perform(get("/api/orders/unknown-correlation-id").with(tokenFor("order-viewer")))
                .andExpect(status().isNotFound());
    }

    @Test
    void letsAnAdminCreateAnOrderAndReportPayment() throws Exception {
        String correlationId = createOrder();

        mockMvc.perform(post("/api/orders/{correlationId}/payment", correlationId)
                        .with(tokenFor("order-admin")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders/{correlationId}", correlationId).with(tokenFor("order-viewer")))
                .andExpect(status().isOk());
    }

    @Test
    void letsAnAdminDeleteAnOrderAfterWhichItIsGoneFromTheApi() throws Exception {
        String correlationId = createOrder();

        mockMvc.perform(delete("/api/orders/{correlationId}", correlationId).with(tokenFor("order-admin")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/orders/{correlationId}", correlationId).with(tokenFor("order-viewer")))
                .andExpect(status().isNotFound());

        // Already gone: 404, not a second successful delete.
        mockMvc.perform(delete("/api/orders/{correlationId}", correlationId).with(tokenFor("order-admin")))
                .andExpect(status().isNotFound());
    }

    /**
     * The UI and the API are served by different filter chains, and which one
     * answers is visible in the response: the UI sends a browser to log in, the
     * API answers a missing token with 401. A securityMatcher that accidentally
     * swallowed {@code /orders} would turn the demo pages into a JSON 401.
     * Authorization of the UI chain itself is covered by {@link OrderUiSecurityTest}.
     */
    @Test
    void getOrders_whenRequestTargetsUiPath_thenRoutesToUiChainNotApiChain() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/keycloak"));
    }

    private String createOrder() throws Exception {
        String response = mockMvc.perform(post("/api/orders")
                        .with(tokenFor("order-admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_ORDER_BODY))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return response.replaceAll(".*\"correlationId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    /**
     * Authorities are set explicitly rather than via claims because this post
     * processor builds the authentication directly and never runs the
     * application's converter - that mapping is covered by
     * {@link KeycloakRealmRoleConverterTest}.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor tokenFor(String... realmRoles) {
        SimpleGrantedAuthority[] authorities = java.util.Arrays.stream(realmRoles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toArray(SimpleGrantedAuthority[]::new);

        return jwt().authorities(authorities);
    }
}
