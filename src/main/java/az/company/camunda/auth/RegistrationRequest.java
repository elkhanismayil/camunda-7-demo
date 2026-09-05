package az.company.camunda.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "A self-service sign-up. The password is forwarded to Keycloak and never stored here.")
public record RegistrationRequest(

        @Schema(description = "Login name, unique within the realm", example = "dave", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 3, max = 60)
        @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "must contain only letters, digits, dot, underscore or hyphen")
        String username,

        @Schema(example = "dave@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @Schema(example = "Dave", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 60)
        String firstName,

        @Schema(example = "Devlin", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 60)
        String lastName,

        @Schema(description = "Checked against the realm password policy by Keycloak, not here",
                example = "s3cret-passphrase", format = "password", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 8, max = 128)
        String password) {

    // The generated record toString() prints every component, so one log line,
    // one debugger view or one framework error dump would carry the password.
    // The credential is forwarded to Keycloak and never stored or printed here.
    @Override
    public String toString() {
        return "RegistrationRequest[username=" + username
                + ", email=" + email
                + ", firstName=" + firstName
                + ", lastName=" + lastName
                + ", password=***]";
    }
}
