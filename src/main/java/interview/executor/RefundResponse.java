package interview.executor;

import java.util.UUID;

public record RefundResponse(
        UUID operationId,
        OperationStatus status,
        String stripeRefundId) {
}
