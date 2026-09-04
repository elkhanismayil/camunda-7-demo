package az.company.camunda.exception;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * The single error body every failed API call returns. One shape means a client
 * writes one error handler instead of one per endpoint.
 */
@Schema(description = "Error body returned by every failed API call")
public record ErrorResponse(

        @Schema(description = "HTTP status, repeated here so it survives logging and proxies", example = "404")
        int status,

        @Schema(description = "Stable application code - branch on this, not on the message",
                example = "ORDER_NOT_FOUND")
        String errorCode,

        @Schema(description = "Human-readable explanation, localized per Accept-Language",
                example = "No order was found for correlation ID 6f1c-....")
        String message,

        @Schema(description = "When the failure was produced")
        Instant timestamp,

        @Schema(description = "Request URI that failed", example = "/api/orders/6f1c-...")
        String path,

        @Schema(description = "Ties this response to the server-side log entry; also sent as the X-Trace-Id header")
        String traceId
) {
}
