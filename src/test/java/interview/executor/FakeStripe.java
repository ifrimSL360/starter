package interview.executor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared provider fake for PostgreSQL-backed tests.
 *
 * <p>It models provider-side idempotency: the first refund for an operation ID
 * wins, and a repeated call with the same data returns the original refund ID.
 * A repeated operation ID with different data is rejected.
 *
 * <p>Failure and blocking are one-shot. Pass an operation ID to scope them to
 * one operation; pass nothing when only one operation is in flight.
 */
public final class FakeStripe implements Stripe {

    public enum FailureMode {
        NONE,
        BEFORE_EXECUTION,
        AFTER_EXECUTION
    }

    public record Refund(UUID operationId, String customerId, RefundRequest request, String refundId) {
    }

    private final Map<UUID, Refund> completed = new ConcurrentHashMap<>();
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger refunds = new AtomicInteger();
    private final AtomicReference<UUID> failureScope = new AtomicReference<>();
    private final AtomicReference<FailureMode> nextFailure = new AtomicReference<>(FailureMode.NONE);
    private final AtomicReference<UUID> gateScope = new AtomicReference<>();
    private final AtomicReference<CallGate> nextGate = new AtomicReference<>();

    /** Fails the next provider call, whichever operation it belongs to. */
    public void failNext(FailureMode mode) {
        failNext(null, mode);
    }

    /** Fails the next provider call for one operation. */
    public void failNext(UUID operationId, FailureMode mode) {
        failureScope.set(operationId);
        nextFailure.set(mode);
    }

    /** Blocks the next provider call, whichever operation it belongs to. */
    public CallGate blockNextAttempt() {
        return blockNextAttempt(null);
    }

    /** Blocks the next provider call for one operation. */
    public CallGate blockNextAttempt(UUID operationId) {
        CallGate gate = new CallGate();
        if (!nextGate.compareAndSet(null, gate)) {
            throw new IllegalStateException("An attempt is already blocked");
        }
        gateScope.set(operationId);
        return gate;
    }

    public int attempts() {
        return attempts.get();
    }

    public int refundCount() {
        return refunds.get();
    }

    public List<Refund> refunds() {
        return List.copyOf(completed.values());
    }

    @Override
    public String refund(UUID operationId, String customerId, RefundRequest request)
            throws StripeException {
        attempts.incrementAndGet();

        CallGate gate = takeGate(operationId);
        if (gate != null) {
            gate.awaitRelease();
        }

        Refund existing = completed.get(operationId);
        if (existing != null) {
            requireSameRequest(existing, customerId, request);
            return existing.refundId();
        }

        FailureMode failure = takeFailure(operationId);
        if (failure == FailureMode.BEFORE_EXECUTION) {
            throw new StripeException(
                    StripeException.Outcome.DEFINITELY_NOT_EXECUTED,
                    "Stripe rejected before creating a refund");
        }

        Refund created = createRefund(operationId, customerId, request);
        requireSameRequest(created, customerId, request);

        if (failure == FailureMode.AFTER_EXECUTION) {
            throw new StripeException(
                    StripeException.Outcome.UNKNOWN,
                    "Stripe created the refund but the response was lost");
        }
        return created.refundId();
    }

    private Refund createRefund(UUID operationId, String customerId, RefundRequest request) {
        synchronized (completed) {
            Refund existing = completed.get(operationId);
            if (existing != null) {
                return existing;
            }
            Refund created = new Refund(operationId, customerId, request,
                    "re_demo_" + refunds.incrementAndGet());
            completed.put(operationId, created);
            return created;
        }
    }

    private CallGate takeGate(UUID operationId) {
        UUID scope = gateScope.get();
        if (scope != null && !scope.equals(operationId)) {
            return null;
        }
        gateScope.compareAndSet(scope, null);
        return nextGate.getAndSet(null);
    }

    private FailureMode takeFailure(UUID operationId) {
        UUID scope = failureScope.get();
        if (scope != null && !scope.equals(operationId)) {
            return FailureMode.NONE;
        }
        failureScope.compareAndSet(scope, null);
        return nextFailure.getAndSet(FailureMode.NONE);
    }

    private static void requireSameRequest(Refund refund, String customerId, RefundRequest request)
            throws StripeException {
        if (!refund.customerId().equals(customerId) || !refund.request().equals(request)) {
            throw new StripeException(
                    StripeException.Outcome.DEFINITELY_NOT_EXECUTED,
                    "Operation ID reused with different refund data");
        }
    }

    /** Lets a test hold a provider call open and release it deterministically. */
    public static final class CallGate implements AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        /** Waits until a provider call reached the gate. Returns false on timeout. */
        public boolean awaitEntered(Duration timeout) throws InterruptedException {
            return entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        public void release() {
            release.countDown();
        }

        @Override
        public void close() {
            release();
        }

        void awaitRelease() throws StripeException {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new StripeException(
                        StripeException.Outcome.UNKNOWN,
                        "Interrupted while the provider call was blocked",
                        interrupted);
            }
        }
    }
}
