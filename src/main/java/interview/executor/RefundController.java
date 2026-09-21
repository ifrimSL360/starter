package interview.executor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * HTTP seam of the refund API.
 *
 * <p>The customer header is optional to Spring and checked by the service, so
 * a missing and a blank header fail the same rule in one place.
 */
@RestController
@RequestMapping("/v1/refunds")
public class RefundController {
    private final RefundService service;

    public RefundController(RefundService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RefundResponse> submit(
            @RequestHeader(value = "X-Customer-Id", required = false) String customerId,
            @RequestBody(required = false) RefundRequest request) {
        RefundService.SubmitResult result = service.submit(customerId, request);
        return ResponseEntity.status(statusOf(result.outcome())).body(result.response());
    }

    /**
     * The path variable is a string rather than a UUID so that an unparseable
     * value is answered like any other unknown operation. Letting Spring bind it
     * would return 400 and tell a caller that its guess was at least well formed.
     */
    @GetMapping("/{operationId}")
    public ResponseEntity<RefundResponse> get(
            @RequestHeader(value = "X-Customer-Id", required = false) String customerId,
            @PathVariable String operationId) {
        return ResponseEntity.ok(service.get(parse(operationId), customerId)
                .orElseThrow(OperationNotFoundException::new));
    }

    private static HttpStatus statusOf(RefundService.Outcome outcome) {
        return switch (outcome) {
            case CREATED -> HttpStatus.ACCEPTED;
            case REPLAYED -> HttpStatus.OK;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
    }

    private static UUID parse(String operationId) {
        try {
            return UUID.fromString(operationId);
        } catch (IllegalArgumentException malformed) {
            throw new OperationNotFoundException();
        }
    }
}
