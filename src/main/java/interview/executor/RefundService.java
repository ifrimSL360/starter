package interview.executor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creates and replays durable refund operations.
 *
 * <p>The identity of an operation is the customer and payment pair, and the
 * unique index on that pair is the only thing that decides who creates it. No
 * state here is held in the JVM, so any instance answers a repeat identically.
 */
@Service
public class RefundService {

    /** Which of the three POST outcomes the contract calls for. */
    public enum Outcome {
        CREATED,
        REPLAYED,
        CONFLICT
    }

    public record SubmitResult(Outcome outcome, RefundResponse response) {
    }

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private static final String INSERT = """
            insert into refund_operation (
                    operation_id, customer_id, payment_id, amount_minor, currency,
                    status, created_at, updated_at)
            values (?, ?, ?, ?, ?, 'PENDING', ?, ?)
            on conflict (customer_id, payment_id) do nothing
            returning operation_id, amount_minor, currency, status, stripe_refund_id
            """;

    private static final String SELECT_BY_PAIR = """
            select operation_id, amount_minor, currency, status, stripe_refund_id
            from refund_operation
            where customer_id = ? and payment_id = ?
            """;

    private static final String SELECT_BY_OWNER = """
            select operation_id, amount_minor, currency, status, stripe_refund_id
            from refund_operation
            where operation_id = ? and customer_id = ?
            """;

    private static final RowMapper<StoredOperation> MAPPER = (rows, number) -> new StoredOperation(
            rows.getObject("operation_id", UUID.class),
            rows.getLong("amount_minor"),
            rows.getString("currency"),
            OperationStatus.valueOf(rows.getString("status")),
            rows.getString("stripe_refund_id"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RefundService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Stage 1: create or replay a durable refund operation.
     *
     * <p>One statement decides it. {@code on conflict ... do nothing} returns the
     * inserted row or nothing at all, so the database, not this method, resolves
     * a duplicate. A concurrent inserter blocks on the winner's transaction and
     * then sees an empty result, which is the signal to replay.
     *
     * <p>Stage 2 adds the provider call. Nothing here touches {@link Stripe}.
     */
    public SubmitResult submit(String customerId, RefundRequest request) {
        validate(customerId, request);

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<StoredOperation> created = jdbc.query(INSERT, MAPPER,
                UUID.randomUUID(), customerId, request.paymentId(),
                request.amountMinor(), request.currency(), now, now);
        if (!created.isEmpty()) {
            return new SubmitResult(Outcome.CREATED, response(created.get(0)));
        }

        // The insert conflicted, so a row for this pair is committed. Reading it
        // back needs a fresh statement snapshot, which read committed gives every
        // statement. This method runs without a surrounding transaction on
        // purpose: under repeatable read the select would reuse the transaction
        // snapshot taken before the winner committed and find nothing.
        StoredOperation existing = jdbc.query(SELECT_BY_PAIR, MAPPER, customerId, request.paymentId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "insert reported a conflict but no row for the pair is visible"));

        boolean sameRequest = existing.amountMinor() == request.amountMinor()
                && existing.currency().equals(request.currency());
        return new SubmitResult(sameRequest ? Outcome.REPLAYED : Outcome.CONFLICT, response(existing));
    }

    /**
     * Read a refund only in the supplied customer's namespace.
     *
     * <p>Ownership is part of the where clause, so an operation owned by another
     * customer and an operation that does not exist produce the same empty result.
     */
    public Optional<RefundResponse> get(UUID operationId, String customerId) {
        requireCustomerId(customerId);
        return jdbc.query(SELECT_BY_OWNER, MAPPER, operationId, customerId)
                .stream()
                .findFirst()
                .map(RefundService::response);
    }

    /**
     * The four acceptance rules. They are a superset of the table's check
     * constraints on purpose: a violation that reached PostgreSQL would surface
     * as HTTP 500 rather than 400.
     */
    private static void validate(String customerId, RefundRequest request) {
        requireCustomerId(customerId);
        if (request == null) {
            throw new InvalidRequestException("a request body is required");
        }
        if (request.paymentId() == null || request.paymentId().isBlank()) {
            throw new InvalidRequestException("paymentId must not be blank");
        }
        if (request.amountMinor() <= 0) {
            throw new InvalidRequestException("amountMinor must be a positive number of minor units");
        }
        if (request.currency() == null || !CURRENCY.matcher(request.currency()).matches()) {
            throw new InvalidRequestException("currency must be a three-letter uppercase code");
        }
    }

    private static void requireCustomerId(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new InvalidRequestException("X-Customer-Id must not be blank");
        }
    }

    private static RefundResponse response(StoredOperation stored) {
        return new RefundResponse(stored.operationId(), stored.status(), stored.stripeRefundId());
    }

    private record StoredOperation(
            UUID operationId,
            long amountMinor,
            String currency,
            OperationStatus status,
            String stripeRefundId) {
    }
}
