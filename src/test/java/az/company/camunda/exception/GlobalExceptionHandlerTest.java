package az.company.camunda.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A bare {@code 404} tells the caller nothing: not which identifier was
 * rejected, not whether the service even understood the request, and nothing
 * that ties the failure to a log line. These tests pin the body that replaces
 * it - including the {@code traceId}, which is the only field that makes a
 * user-reported error findable afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    private static final String UNKNOWN_CORRELATION_ID = "unknown-correlation-id";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getOrder_whenTheOrderDoesNotExist_thenAnswersNotFoundWithTheSharedErrorShape() throws Exception {
        // Given no order carries this correlation id

        // When
        // Then
        mockMvc.perform(get("/api/orders/{correlationId}", UNKNOWN_CORRELATION_ID).with(viewerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/orders/" + UNKNOWN_CORRELATION_ID))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void getOrder_whenAcceptLanguageIsSpanish_thenLocalizesTheErrorMessage() throws Exception {
        // Given a caller that asks for Spanish and sends no locale cookie

        // When
        // Then
        mockMvc.perform(get("/api/orders/{correlationId}", UNKNOWN_CORRELATION_ID)
                        .with(viewerToken())
                        .header("Accept-Language", "es"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("No se ha encontrado ningún pedido con el ID de correlación "
                                + UNKNOWN_CORRELATION_ID + "."));
    }

    @Test
    void getOrder_whenNoAcceptLanguageIsSent_thenLocalizesTheErrorMessageInAzerbaijani() throws Exception {
        // Given
        // When
        // Then
        mockMvc.perform(get("/api/orders/{correlationId}", UNKNOWN_CORRELATION_ID).with(viewerToken()))
                .andExpect(jsonPath("$.message")
                        .value(UNKNOWN_CORRELATION_ID + " korrelyasiya ID-si ilə sifariş tapılmadı."));
    }

    @Test
    void getOrder_whenTheOrderDoesNotExist_thenReturnsTheSameTraceIdInTheBodyAndTheHeader() throws Exception {
        // Given
        // When
        String actualTraceId = mockMvc.perform(get("/api/orders/{correlationId}", UNKNOWN_CORRELATION_ID)
                        .with(viewerToken()))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(UUID_PATTERN)))
                .andReturn()
                .getResponse()
                .getHeader(TRACE_ID_HEADER);

        // Then
        assertThat(actualTraceId)
                .as("the header is what an operator copies out of the browser, so it must match the body")
                .matches(UUID_PATTERN);
    }

    @Test
    void getOrder_whenTheOrderDoesNotExist_thenLeaksNoInternals() throws Exception {
        // Given
        // When
        String actualBody = mockMvc.perform(get("/api/orders/{correlationId}", UNKNOWN_CORRELATION_ID)
                        .with(viewerToken()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Then
        assertThat(actualBody).doesNotContain("Exception", "az.company.camunda", "at java.", "select ");
    }

    @Test
    void deleteOrder_whenTheOrderDoesNotExist_thenAnswersNotFoundWithTheSharedErrorShape() throws Exception {
        // Given
        // When
        // Then
        mockMvc.perform(delete("/api/orders/{correlationId}", UNKNOWN_CORRELATION_ID).with(adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
    }

    @Test
    void notifyPaymentReceived_whenNoProcessInstanceIsWaiting_thenAnswersNotFoundWithTheSharedErrorShape()
            throws Exception {
        // Given
        // When
        // Then
        mockMvc.perform(post("/api/orders/{correlationId}/payment", UNKNOWN_CORRELATION_ID).with(adminToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"))
                .andExpect(header().exists(TRACE_ID_HEADER));
    }

    private static RequestPostProcessor viewerToken() {
        return tokenFor("order-viewer");
    }

    private static RequestPostProcessor adminToken() {
        return tokenFor("order-admin");
    }

    private static RequestPostProcessor tokenFor(String... realmRoles) {
        SimpleGrantedAuthority[] authorities = Arrays.stream(realmRoles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toArray(SimpleGrantedAuthority[]::new);

        return jwt().authorities(authorities);
    }
}
