package interview.executor;

/**
 * Thrown when a request fails one of the stage 1 acceptance rules. Mapped to
 * HTTP 400 by {@link RefundExceptionHandler}.
 */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
