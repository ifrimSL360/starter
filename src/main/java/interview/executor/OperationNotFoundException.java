package interview.executor;

/**
 * Thrown when an operation is unknown or is owned by another customer. The two
 * cases are deliberately indistinguishable: HTTP 403 would confirm that the
 * operation exists.
 */
public class OperationNotFoundException extends RuntimeException {
}
