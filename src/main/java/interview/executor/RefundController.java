package interview.executor;

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
 * <p>The status codes in this skeleton are placeholders: {@code 202} is only
 * correct for a newly created operation. See the HTTP contract in README.md for
 * the codes that replay, conflict, and missing operations require.
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
            @RequestHeader("X-Customer-Id") String customerId,
            @RequestBody RefundRequest request) {
        return ResponseEntity.accepted()
                .body(service.submit(customerId, request));
    }

    @GetMapping("/{operationId}")
    public ResponseEntity<RefundResponse> get(
            @RequestHeader("X-Customer-Id") String customerId,
            @PathVariable UUID operationId) {
        return ResponseEntity.ok(service.get(operationId, customerId));
    }
}
