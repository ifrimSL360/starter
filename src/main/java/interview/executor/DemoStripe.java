package interview.executor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-in provider used when the application does not supply its own
 * {@link Stripe} bean.
 *
 * <p>It models provider-side idempotency: the first refund for an operation ID
 * wins, and a repeated call with the same data returns the original refund ID.
 * A repeated operation ID with different data is rejected, as a real provider
 * would reject a reused idempotency key.
 */
public class DemoStripe implements Stripe {
    private final Map<UUID, Refund> completed = new ConcurrentHashMap<>();
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger refunds = new AtomicInteger();

    @Override
    public synchronized String refund(UUID operationId, String customerId, RefundRequest request)
            throws StripeException {
        attempts.incrementAndGet();
        Refund existing = completed.get(operationId);
        if (existing != null) {
            requireSameRequest(existing, customerId, request);
            return existing.refundId();
        }
        Refund created = new Refund(customerId, request, "demo-refund-" + refunds.incrementAndGet());
        completed.put(operationId, created);
        return created.refundId();
    }

    public int attempts() {
        return attempts.get();
    }

    public int refunds() {
        return refunds.get();
    }

    private static void requireSameRequest(Refund refund, String customerId, RefundRequest request)
            throws StripeException {
        if (!refund.customerId().equals(customerId) || !refund.request().equals(request)) {
            throw new StripeException(
                    StripeException.Outcome.DEFINITELY_NOT_EXECUTED,
                    "Operation ID reused with different refund data");
        }
    }

    private record Refund(String customerId, RefundRequest request, String refundId) {
    }
}
