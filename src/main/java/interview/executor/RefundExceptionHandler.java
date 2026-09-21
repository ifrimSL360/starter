package interview.executor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Error bodies for the refund API.
 *
 * <p>There is deliberately no handler for {@link Exception}: Spring already maps
 * an unreadable body and other binding failures to HTTP 400, and a catch-all
 * here would turn those into 500s.
 */
@RestControllerAdvice
public class RefundExceptionHandler {

    /** Carries a fixed message only, so no operation or request data can leak. */
    public record ApiError(String error) {
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> invalidRequest(InvalidRequestException invalid) {
        return ResponseEntity.badRequest().body(new ApiError(invalid.getMessage()));
    }

    @ExceptionHandler(OperationNotFoundException.class)
    ResponseEntity<ApiError> notFound(OperationNotFoundException missing) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("operation not found"));
    }
}
