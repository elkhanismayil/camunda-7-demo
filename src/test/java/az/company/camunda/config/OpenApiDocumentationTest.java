package az.company.camunda.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The published contract is the one piece of documentation a caller actually
 * reads. Generating it from the annotations means it cannot drift from the
 * code - but only if something fails when an endpoint or an error shape goes
 * undocumented, which is what this test is for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocumentationTest {

    private static final String API_DOCS = "/v3/api-docs";
    private static final String ORDER_BY_CORRELATION_ID = "$.paths./api/orders/{correlationId}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocs_whenRequested_thenDocumentTheOrderEndpoints() throws Exception {
        // Given
        // When
        // Then
        mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths./api/orders").exists())
                .andExpect(jsonPath(ORDER_BY_CORRELATION_ID).exists());
    }

    @Test
    void apiDocs_whenRequested_thenDocumentTheNotFoundResponseOfTheOrderLookup() throws Exception {
        // Given
        // When
        // Then
        mockMvc.perform(get(API_DOCS))
                .andExpect(jsonPath(ORDER_BY_CORRELATION_ID + ".get.responses.404").exists());
    }

    @Test
    void apiDocs_whenRequested_thenDescribeTheSharedErrorResponseSchema() throws Exception {
        // Given the advice answers every not-found with one body shape

        // When
        // Then
        mockMvc.perform(get(API_DOCS))
                .andExpect(jsonPath("$.components.schemas.ErrorResponse.properties.errorCode").exists())
                .andExpect(jsonPath("$.components.schemas.ErrorResponse.properties.traceId").exists());
    }
}
