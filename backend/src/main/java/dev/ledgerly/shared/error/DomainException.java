package dev.ledgerly.shared.error;

/** Base for every business-rule failure. The message must be safe to show to the client (no PII). */
public class DomainException extends RuntimeException {

    private final ErrorCode code;

    public DomainException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
