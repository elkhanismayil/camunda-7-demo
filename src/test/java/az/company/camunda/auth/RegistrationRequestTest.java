package az.company.camunda.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationRequestTest {

    private static final String PASSWORD = "s3cret-passphrase";

    @Test
    void toString_whenCalled_thenOmitThePassword() {
        // Given a submitted registration
        RegistrationRequest request =
                new RegistrationRequest("dave", "dave@example.com", "Dave", "Devlin", PASSWORD);

        // When something renders it - a log line, a debugger, an error report
        String rendered = request.toString();

        // Then the credential is not in the rendered text
        assertThat(rendered).doesNotContain(PASSWORD);
    }

    @Test
    void toString_whenCalled_thenKeepTheFieldsThatMakeALogUseful() {
        // Given a submitted registration
        RegistrationRequest request =
                new RegistrationRequest("dave", "dave@example.com", "Dave", "Devlin", PASSWORD);

        // When it is rendered
        String rendered = request.toString();

        // Then the username is still there, or the log line identifies nothing
        assertThat(rendered).contains("dave");
    }
}
