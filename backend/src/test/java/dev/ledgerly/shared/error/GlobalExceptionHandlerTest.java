package dev.ledgerly.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    @Test
    void domainExceptionMapsToItsCodeAndStatus() {
        var response = handler.handleDomain(new DomainException(ErrorCode.VALIDATION_FAILED, "Amount must be positive."));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var body = (ProblemDetail) response.getBody();
        assertThat(body.getProperties()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(body.getDetail()).isEqualTo("Amount must be positive.");
    }

    @Test
    void unexpectedExceptionNeverLeaksItsMessage() {
        var response = handler.handleUnexpected(new IllegalStateException("db password is hunter2"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        var body = (ProblemDetail) response.getBody();
        assertThat(body.getDetail()).doesNotContain("hunter2");
        assertThat(body.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }
}
