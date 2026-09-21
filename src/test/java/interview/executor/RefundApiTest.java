package interview.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stage 1 acceptance cases, exercised over HTTP against real PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefundApiTest extends PostgresTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final FakeStripe STRIPE = new FakeStripe();

    @TestConfiguration
    static class Provider {
        @Bean
        Stripe stripe() {
            return STRIPE;
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("truncate table refund_operation");
    }

    @Test
    void newRequestReturns202WithNewIdAndPending() {
        ResponseEntity<String> created = post("customer-a", body("payment-123", 2500, "USD"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(operationId(created)).isNotBlank();
        assertThat(field(created, "status")).isEqualTo("PENDING");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void getReturnsSameOperationIdAndOmitsStripeRefundId() {
        ResponseEntity<String> created = post("customer-a", body("payment-123", 2500, "USD"));

        ResponseEntity<String> found = get("customer-a", operationId(created));

        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(operationId(found)).isEqualTo(operationId(created));
        assertThat(field(found, "status")).isEqualTo("PENDING");
        assertThat(json(found).has("stripeRefundId")).isFalse();
    }

    @Test
    void exactRepeatReturns200WithSameIdAndLeavesOneRow() {
        ResponseEntity<String> created = post("customer-a", body("payment-123", 2500, "USD"));
        ResponseEntity<String> repeated = post("customer-a", body("payment-123", 2500, "USD"));

        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(operationId(repeated)).isEqualTo(operationId(created));
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void thirdIdenticalRepeatStillReturns200WithSameId() {
        ResponseEntity<String> created = post("customer-a", body("payment-123", 2500, "USD"));
        post("customer-a", body("payment-123", 2500, "USD"));
        ResponseEntity<String> third = post("customer-a", body("payment-123", 2500, "USD"));

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(operationId(third)).isEqualTo(operationId(created));
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void repeatWithDifferentAmountReturns409() {
        post("customer-a", body("payment-123", 2500, "USD"));

        ResponseEntity<String> conflict = post("customer-a", body("payment-123", 9900, "USD"));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void conflictingAmountLeavesStoredAmountUnchanged() {
        post("customer-a", body("payment-123", 2500, "USD"));

        post("customer-a", body("payment-123", 9900, "USD"));

        assertThat(storedLong("amount_minor")).isEqualTo(2500L);
    }

    @Test
    void repeatWithDifferentCurrencyReturns409() {
        post("customer-a", body("payment-123", 2500, "USD"));

        ResponseEntity<String> conflict = post("customer-a", body("payment-123", 2500, "EUR"));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void conflictingCurrencyLeavesStoredCurrencyUnchanged() {
        post("customer-a", body("payment-123", 2500, "USD"));

        post("customer-a", body("payment-123", 2500, "EUR"));

        assertThat(storedString("currency")).isEqualTo("USD");
    }

    @Test
    void nonOwnerGetReturns404() {
        ResponseEntity<String> created = post("customer-a", body("payment-123", 2500, "USD"));

        ResponseEntity<String> denied = get("customer-b", operationId(created));

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nonOwnerGetBodyContainsNoOperationIdRefundIdOrRequestData() {
        ResponseEntity<String> created = post("customer-a", body("payment-123", 2500, "USD"));
        String id = operationId(created);

        ResponseEntity<String> denied = get("customer-b", id);

        String body = denied.getBody() == null ? "" : denied.getBody();
        assertThat(body).doesNotContain(id);
        assertThat(body).doesNotContain("payment-123");
        assertThat(body).doesNotContain("2500");
        assertThat(body).doesNotContain("USD");
        assertThat(denied.getHeaders().toString()).doesNotContain(id);
    }

    @Test
    void samePaymentUnderDifferentCustomerCreatesSeparateOperation() {
        ResponseEntity<String> first = post("customer-a", body("payment-123", 2500, "USD"));
        ResponseEntity<String> second = post("customer-b", body("payment-123", 2500, "USD"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(operationId(second)).isNotEqualTo(operationId(first));
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void punctuatedCustomerAndPaymentIdsDoNotCollide() {
        ResponseEntity<String> first = post("cust-a.b", body("pay_1:2", 2500, "USD"));
        ResponseEntity<String> second = post("cust-a_b", body("pay-1:2", 2500, "USD"));
        ResponseEntity<String> third = post("cust.a-b", body("pay:1_2", 2500, "USD"));

        assertThat(operationId(first)).isNotEqualTo(operationId(second));
        assertThat(operationId(second)).isNotEqualTo(operationId(third));
        assertThat(rowCount()).isEqualTo(3);
    }

    @Test
    void concurrentDuplicatesYieldExactlyOne202AndSevenReplays() throws Exception {
        List<ResponseEntity<String>> responses = raceEightDuplicates();

        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.ACCEPTED).hasSize(1);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.OK).hasSize(7);
    }

    @Test
    void concurrentDuplicatesLeaveOneRowAndOneDistinctId() throws Exception {
        List<ResponseEntity<String>> responses = raceEightDuplicates();

        assertThat(responses.stream().map(RefundApiTest::operationId).distinct()).hasSize(1);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void acceptedOperationIsVisibleOnAFreshJdbcConnection() throws Exception {
        ResponseEntity<String> created = post("customer-a", body("payment-123", 2500, "USD"));
        String id = operationId(created);

        try (Connection connection = DriverManager.getConnection(
                postgres().getJdbcUrl(), postgres().getUsername(), postgres().getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "select customer_id, status from refund_operation where operation_id = ?")) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("customer_id")).isEqualTo("customer-a");
                assertThat(rows.getString("status")).isEqualTo("PENDING");
            }
        }
    }

    @Test
    void acceptingAnOperationLeavesFakeProviderAttemptsAtZero() {
        int before = STRIPE.attempts();

        post("customer-a", body("payment-123", 2500, "USD"));
        post("customer-a", body("payment-123", 2500, "USD"));

        assertThat(STRIPE.attempts()).isEqualTo(before).isZero();
        assertThat(STRIPE.refundCount()).isZero();
    }

    @Test
    void missingCustomerHeaderReturns400() {
        assertThat(post(null, body("payment-123", 2500, "USD")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankPaymentIdReturns400() {
        assertThat(post("customer-a", body("   ", 2500, "USD")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void zeroAmountReturns400() {
        assertThat(post("customer-a", body("payment-123", 0, "USD")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void negativeAmountReturns400() {
        assertThat(post("customer-a", body("payment-123", -1, "USD")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingCurrencyReturns400() {
        assertThat(post("customer-a", "{\"paymentId\":\"payment-123\",\"amountMinor\":2500}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lowercaseCurrencyReturns400() {
        assertThat(post("customer-a", body("payment-123", 2500, "usd")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankCustomerHeaderOnPostReturns400() {
        assertThat(post("   ", body("payment-123", 2500, "USD")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankCustomerHeaderOnGetReturns400() {
        ResponseEntity<String> created = post("customer-a", body("payment-123", 2500, "USD"));

        assertThat(get("   ", operationId(created)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void malformedUuidInPathReturns404() {
        assertThat(get("customer-a", "not-a-uuid").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownWellFormedUuidReturns404() {
        assertThat(get("customer-a", UUID.randomUUID().toString()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void malformedJsonBodyReturns400() {
        assertThat(post("customer-a", "{\"paymentId\":").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void absentRequestBodyReturns400() {
        assertThat(post("customer-a", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void twoLetterCurrencyReturns400() {
        assertThat(post("customer-a", body("payment-123", 2500, "US")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void fourLetterCurrencyReturns400() {
        assertThat(post("customer-a", body("payment-123", 2500, "USDX")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private List<ResponseEntity<String>> raceEightDuplicates() throws Exception {
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ResponseEntity<String>>> pending = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                pending.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return post("customer-a", body("payment-123", 2500, "USD"));
                }));
            }
            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : pending) {
                responses.add(future.get(20, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private ResponseEntity<String> post(String customerId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (customerId != null) {
            headers.set("X-Customer-Id", customerId);
        }
        return http.exchange("/v1/refunds", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String customerId, String operationId) {
        HttpHeaders headers = new HttpHeaders();
        if (customerId != null) {
            headers.set("X-Customer-Id", customerId);
        }
        return http.exchange("/v1/refunds/" + operationId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private static String body(String paymentId, long amountMinor, String currency) {
        return "{\"paymentId\":\"" + paymentId + "\",\"amountMinor\":" + amountMinor
                + ",\"currency\":\"" + currency + "\"}";
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return JSON.readTree(response.getBody());
        } catch (Exception failure) {
            throw new AssertionError("not JSON: " + response.getBody(), failure);
        }
    }

    private static String field(ResponseEntity<String> response, String name) {
        return json(response).get(name).asText();
    }

    private static String operationId(ResponseEntity<String> response) {
        return field(response, "operationId");
    }

    private int rowCount() {
        Integer count = jdbc.queryForObject("select count(*) from refund_operation", Integer.class);
        return count == null ? 0 : count;
    }

    private long storedLong(String column) {
        Long value = jdbc.queryForObject("select " + column + " from refund_operation", Long.class);
        return value == null ? 0L : value;
    }

    private String storedString(String column) {
        return jdbc.queryForObject("select " + column + " from refund_operation", String.class);
    }
}
