package interview.executor;

import java.util.UUID;

/**
 * Small application seam for the payment provider. It is intentionally not
 * Stripe's public SDK or HTTP interface.
 *
 * <p>The interview materials depend on this exact signature. Do not change it.
 *
 * <p>The operation ID is the provider idempotency key. A repeated call with the
 * same operation ID and the same request data must return the original provider
 * refund ID and must not create a second refund.
 */
public interface Stripe {

    /**
     * Requests one full refund for the operation.
     *
     * @return the provider refund ID
     * @throws StripeException with {@code DEFINITELY_NOT_EXECUTED} when the
     *                         provider confirms that no refund was created, or
     *                         with {@code UNKNOWN} when the outcome cannot be
     *                         established
     */
    String refund(UUID operationId, String customerId, RefundRequest request) throws StripeException;
}
