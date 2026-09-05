package az.company.camunda.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Scoped to @RestController so the Thymeleaf controllers keep rendering HTML on
// failure instead of answering a browser with a JSON body.
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String TRACE_ID_KEY = "traceId";
    private static final String VALIDATION_FAILED_CODE = "VALIDATION_FAILED";
    private static final String MALFORMED_REQUEST_CODE = "MALFORMED_REQUEST";
    private static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorResponse> handleConflict(ConflictException exception, HttpServletRequest request) {
        return clientError(HttpStatus.CONFLICT, exception, request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException exception,
                                                       HttpServletRequest request) {
        return clientError(HttpStatus.BAD_REQUEST, exception, request);
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    ResponseEntity<ErrorResponse> handleUpstreamUnavailable(UpstreamUnavailableException exception,
                                                            HttpServletRequest request) {
        return upstreamFailure(HttpStatus.BAD_GATEWAY, exception, request);
    }

    @ExceptionHandler(UpstreamTimeoutException.class)
    ResponseEntity<ErrorResponse> handleUpstreamTimeout(UpstreamTimeoutException exception,
                                                        HttpServletRequest request) {
        return upstreamFailure(HttpStatus.GATEWAY_TIMEOUT, exception, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidationFailure(MethodArgumentNotValidException exception,
                                                          HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ErrorResponse.FieldError(fieldError.getField(),
                        fieldError.getDefaultMessage()))
                .toList();

        String traceId = traceId();
        log.warn("[{}] {} {} rejected {} field(s)", traceId, request.getMethod(), request.getRequestURI(),
                fieldErrors.size());

        // The offending fields are listed: a single "validation failed" forces the
        // caller to guess which one it was.
        return respond(HttpStatus.BAD_REQUEST, VALIDATION_FAILED_CODE, "The request payload is invalid.",
                request, traceId, fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception,
                                                       HttpServletRequest request) {
        String traceId = traceId();
        // The parser message names our own classes and the offending byte offset.
        log.warn("[{}] {} {} carried a body that could not be parsed", traceId, request.getMethod(),
                request.getRequestURI(), exception);

        return respond(HttpStatus.BAD_REQUEST, MALFORMED_REQUEST_CODE, "The request body could not be read.",
                request, traceId, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request)
            throws Exception {
        // Spring Security translates these into 401/403 further out in the filter
        // chain. Answering them here would turn every denial into a 500.
        if (exception instanceof AccessDeniedException || exception instanceof AuthenticationException) {
            throw exception;
        }

        String traceId = traceId();
        log.error("[{}] {} {} failed unexpectedly", traceId, request.getMethod(), request.getRequestURI(),
                exception);

        return respond(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_CODE,
                "The request could not be completed. Quote the traceId when reporting this.",
                request, traceId, List.of());
    }

    private ResponseEntity<ErrorResponse> clientError(HttpStatus status, ApplicationException exception,
                                                      HttpServletRequest request) {
        String traceId = traceId();
        log.warn("[{}] {} {} rejected: {}", traceId, request.getMethod(), request.getRequestURI(),
                exception.getMessage());

        return respond(status, exception.getErrorCode(), exception.getMessage(), request, traceId, List.of());
    }

    private ResponseEntity<ErrorResponse> upstreamFailure(HttpStatus status, ApplicationException exception,
                                                          HttpServletRequest request) {
        String traceId = traceId();
        log.error("[{}] {} {} failed on an upstream dependency", traceId, request.getMethod(),
                request.getRequestURI(), exception);

        // The message is safe to return because every exception in this family is
        // built from our own words; the upstream's own body stays in the log above.
        return respond(status, exception.getErrorCode(), exception.getMessage(), request, traceId, List.of());
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message,
                                                  HttpServletRequest request, String traceId,
                                                  List<ErrorResponse.FieldError> fieldErrors) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), code, message, Instant.now(), request.getRequestURI(),
                        traceId, fieldErrors));
    }

    private String traceId() {
        // This service has no MDC-populating filter yet. Reading MDC first means the
        // one that a future filter installs is picked up without touching this class.
        String traceId = MDC.get(TRACE_ID_KEY);
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}
