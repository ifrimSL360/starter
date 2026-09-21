package interview.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement: a committed operation is returned after the application restarts,
 * and a repeat served by a different instance yields the original operation.
 *
 * <p>The row is committed by a plain JDBC write that this context never saw, so
 * the context here is the cold instance. The distinct property gives it a Spring
 * context of its own rather than the one cached for the other test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "interview.context = restarted")
class RefundSurvivesRestartTest extends PostgresTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("truncate table refund_operation");
    }

    @Test
    void operationCommittedByAnotherInstanceIsReadableFromAColdContext() throws Exception {
        UUID committed = seed("customer-a", "payment-123", 2500, "USD");

        ResponseEntity<String> found = get("customer-a", committed.toString());

        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(found.getBody());
        assertThat(body.get("operationId").asText()).isEqualTo(committed.toString());
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void duplicateSubmitFromAColdContextReturnsTheOriginalOperationId() throws Exception {
        UUID committed = seed("customer-a", "payment-123", 2500, "USD");

        ResponseEntity<String> repeated = post("customer-a",
                "{\"paymentId\":\"payment-123\",\"amountMinor\":2500,\"currency\":\"USD\"}");

        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JSON.readTree(repeated.getBody()).get("operationId").asText())
                .isEqualTo(committed.toString());
        Integer rows = jdbc.queryForObject("select count(*) from refund_operation", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    private UUID seed(String customerId, String paymentId, long amountMinor, String currency) {
        UUID operationId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                insert into refund_operation (
                        operation_id, customer_id, payment_id, amount_minor, currency,
                        status, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, operationId, customerId, paymentId, amountMinor, currency, now, now);
        return operationId;
    }

    private ResponseEntity<String> post(String customerId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Customer-Id", customerId);
        return http.exchange("/v1/refunds", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String customerId, String operationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Customer-Id", customerId);
        return http.exchange("/v1/refunds/" + operationId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }
}
