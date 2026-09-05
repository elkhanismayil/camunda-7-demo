package az.company.camunda.auth;

import az.company.camunda.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping(RegistrationController.RESOURCE_PATH)
@Tag(name = "Registrations",
        description = "Self-service sign-up. Creates the account in Keycloak and grants it read-only access.")
public class RegistrationController {

    static final String RESOURCE_PATH = "/api/v1/registrations";

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping
    @Operation(summary = "Register a new user",
            description = """
                    Creates a Keycloak user in the demo realm and grants it the `order-viewer` realm role,
                    which is read-only access to orders. The endpoint is anonymous: a person without an
                    account cannot present a token to ask for one. It returns no token - sign in through
                    the Keycloak login page, or request a token from Keycloak directly.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created and granted order-viewer",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = RegistrationResponse.class),
                            examples = @ExampleObject(value = """
                                    {"id":"1f3c9a2e-0000-4000-8000-000000000001","username":"dave"}"""))),
            @ApiResponse(responseCode = "400",
                    description = "The payload failed validation, or the password was refused by the realm policy",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {"status":400,"code":"VALIDATION_FAILED","message":"The request payload is invalid.",
                                     "timestamp":"2026-09-05T10:15:30Z","path":"/api/v1/registrations",
                                     "traceId":"3f1c...","fieldErrors":[{"field":"email","message":"must be a well-formed email address"}]}"""))),
            @ApiResponse(responseCode = "409", description = "The username is already taken",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {"status":409,"code":"USERNAME_TAKEN","message":"Username 'dave' is already taken.",
                                     "timestamp":"2026-09-05T10:15:30Z","path":"/api/v1/registrations","traceId":"3f1c..."}"""))),
            @ApiResponse(responseCode = "502", description = "Keycloak is unreachable or answered with a failure",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "504", description = "Keycloak did not answer in time",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request) {
        RegistrationResponse registration = registrationService.register(request);

        return ResponseEntity
                .created(URI.create(RESOURCE_PATH + "/" + registration.id()))
                .body(registration);
    }
}
