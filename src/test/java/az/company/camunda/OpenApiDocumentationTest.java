package az.company.camunda;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocumentationTest {

    private static final String API_DOCS_PATH = "/v3/api-docs";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getApiDocs_whenCallerIsAnonymous_thenServeTheSpecification() throws Exception {
        // Given a reader with no account - the specification is how they learn how
        // to obtain one
        // When they fetch the OpenAPI document
        MockHttpServletResponse response = mockMvc.perform(get(API_DOCS_PATH)).andReturn().getResponse();

        // Then it is served rather than hidden behind the login it documents
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void getApiDocs_whenSpecificationIsGenerated_thenItDocumentsTheRegistrationEndpoint() throws Exception {
        // Given the generated specification
        MockHttpServletResponse response = mockMvc.perform(get(API_DOCS_PATH)).andReturn().getResponse();

        // When it is read
        String specification = response.getContentAsString();

        // Then the sign-up contract is in it, with the outcomes a caller must handle
        assertThat(specification)
                .contains("/api/v1/registrations")
                .contains("Register a new user")
                .contains("\"201\"")
                .contains("\"409\"")
                .contains("\"502\"");
    }

    @Test
    void getSwaggerUi_whenCallerIsAnonymous_thenServeTheUi() throws Exception {
        // Given the browsable form of the same specification
        // When it is opened without a session
        MockHttpServletResponse response = mockMvc.perform(get("/swagger-ui/index.html")).andReturn().getResponse();

        // Then it renders
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
