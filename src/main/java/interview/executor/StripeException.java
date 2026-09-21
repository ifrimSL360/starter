package interview.executor;

/**
 * Thrown when the provider call does not return a refund ID.
 *
 * <p>The two outcomes are deliberately different. {@code DEFINITELY_NOT_EXECUTED}
 * means the provider confirmed that no refund exists. {@code UNKNOWN} means the
 * request may have been executed before the response was lost.
 */
public class StripeException extends Exception {
    public enum Outcome {
        DEFINITELY_NOT_EXECUTED,
        UNKNOWN
    }

    private final Outcome outcome;

    public StripeException(Outcome outcome, String message) {
        super(message);
        this.outcome = outcome;
    }

    public StripeException(Outcome outcome, String message, Throwable cause) {
        super(message, cause);
        this.outcome = outcome;
    }

    public Outcome outcome() {
        return outcome;
    }
}
