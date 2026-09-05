package az.company.camunda.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The account that was created. It carries no token - sign in to obtain one.")
public record RegistrationResponse(

        @Schema(description = "Keycloak user id", example = "1f3c9a2e-0000-4000-8000-000000000001")
        String id,

        @Schema(example = "dave")
        String username) {
}
