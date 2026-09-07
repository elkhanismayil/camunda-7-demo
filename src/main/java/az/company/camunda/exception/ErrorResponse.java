package az.company.camunda.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "The single error shape every failing REST call in this service returns.")
public record ErrorResponse(

        @Schema(description = "HTTP status code, repeated in the body so a logged response is self-contained",
                example = "409")
        int status,

        @Schema(description = "Application error code - stable, machine-readable, safe to branch on",
                example = "USERNAME_TAKEN")
        String code,

        @Schema(description = "Human-readable explanation. Never carries upstream or internal detail",
                example = "Username 'dave' is already taken.")
        String message,

        @Schema(description = "When the failure was rendered", example = "2026-09-05T10:15:30Z")
        Instant timestamp,

        @Schema(description = "Request path that failed", example = "/api/v1/registrations")
        String path,

        @Schema(description = "Correlates this response with the log entry that carries the full detail",
                example = "3f1c9b2a4d6e8f10")
        String traceId,

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @Schema(description = "Field-level failures, present only for a validation error")
        List<FieldError> fieldErrors) {

    @Schema(description = "One rejected field and the reason it was rejected.")
    public record FieldError(
            @Schema(example = "email") String field,
            @Schema(example = "must be a well-formed email address") String message) {
    }
}
