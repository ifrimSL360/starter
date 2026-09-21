package interview.executor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class RefundService {
    private final JdbcTemplate jdbc;

    public RefundService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stage 1: create or replay a durable refund operation.
     *
     * The interviewer will add asynchronous Stripe processing later. Do not
     * call Stripe from this stage.
     */
    public RefundResponse submit(String customerId, RefundRequest request) {
        throw new UnsupportedOperationException("Candidate implementation goes here");
    }

    /**
     * Read a refund only in the supplied customer's namespace.
     */
    public RefundResponse get(UUID operationId, String customerId) {
        throw new UnsupportedOperationException("Candidate implementation goes here");
    }
}
