package dev.ledgerly.shared.error;

import dev.ledgerly.shared.web.CorrelationIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Single place that turns exceptions into RFC 7807 problem+json with a stable {@code code}. Never leaks stack traces. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Also used by the security filters, which run outside Spring MVC's exception handling. */
    public static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        String requestId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }

    private static ResponseEntity<Object> respond(ErrorCode code, String detail) {
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(code, detail));
    }

    private final MeterRegistry metrics;

    public GlobalExceptionHandler(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    /** Business rejections by code (INSUFFICIENT_FUNDS, DAILY_LIMIT_EXCEEDED, ...): a spike is a product signal. */
    @ExceptionHandler(DomainException.class)
    ResponseEntity<Object> handleDomain(DomainException ex) {
        metrics.counter("app.rejections", "code", ex.code().name()).increment();
        return respond(ex.code(), ex.getMessage());
    }

    /** Method-security denials are thrown inside controllers, so they reach advice before the security filters. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
        return respond(ErrorCode.FORBIDDEN, "You do not have permission to perform this action.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex) {
        return respond(ErrorCode.UNAUTHENTICATED, "Authentication required.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /** Framework errors (unknown route, malformed JSON, ...) keep Spring's status but get our shape. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem;
        if (statusCode.value() == HttpStatus.NOT_FOUND.value()) {
            problem = problem(ErrorCode.RESOURCE_NOT_FOUND, "The requested resource was not found.");
        } else if (statusCode.is4xxClientError()) {
            problem = problem(ErrorCode.BAD_REQUEST, "The request could not be processed.");
        } else {
            problem = problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
        }
        problem.setStatus(statusCode.value());
        return ResponseEntity.status(statusCode).headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
