package az.company.camunda.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/**
 * Turns domain exceptions into the one {@link ErrorResponse} shape. Controllers
 * therefore never assemble an error body themselves, and the response never
 * carries anything the caller was not meant to see - the exception detail stays
 * in the log, tied to the same {@code traceId} the caller was given.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException exception, HttpServletRequest request) {
        // Generated here rather than read from the MDC: this service runs no
        // trace-context filter yet, and an error response without any handle on
        // the log entry is the expensive half of every support call.
        String traceId = UUID.randomUUID().toString();

        log.warn("[{}] {} {} -> {}", traceId, request.getMethod(), request.getRequestURI(), exception.getMessage());

        ErrorResponse body = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                exception.getErrorCode(),
                localize(exception),
                Instant.now(),
                request.getRequestURI(),
                traceId
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(TRACE_ID_HEADER, traceId)
                .body(body);
    }

    /**
     * The locale comes from the same resolver the UI uses, so an API caller gets
     * the language of its {@code Accept-Language} header and a browser gets the
     * language it picked with {@code ?lang=}.
     */
    private String localize(NotFoundException exception) {
        return messageSource.getMessage(
                exception.getMessageKey(),
                exception.getMessageArgs(),
                LocaleContextHolder.getLocale()
        );
    }
}
