package dev.ledgerly.shared.error;

import org.springframework.http.HttpStatus;

/** Stable, client-facing error codes. Clients switch on these, never on messages. Add codes; never rename them. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    EMAIL_TAKEN(HttpStatus.CONFLICT),
    ACCOUNT_LOCKED(HttpStatus.LOCKED),
    AMOUNT_INVALID(HttpStatus.BAD_REQUEST),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT),
    SAME_ACCOUNT(HttpStatus.UNPROCESSABLE_ENTITY),
    RECIPIENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    DAILY_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
    WALLET_FROZEN(HttpStatus.LOCKED),
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND),
    ALREADY_REVERSED(HttpStatus.CONFLICT),
    NOT_REVERSIBLE(HttpStatus.UNPROCESSABLE_ENTITY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
