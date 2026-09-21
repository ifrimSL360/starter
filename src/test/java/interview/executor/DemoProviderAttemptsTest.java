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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance case 10 as the README states it: through the demo provider's own
 * actuator endpoint.
 *
 * <p>This context deliberately declares no {@link Stripe} bean, so
 * {@code DemoStripe} stays registered and the endpoint reports real counters.
 * A test that installs {@code FakeStripe} makes the demo provider back off and
 * reports {@code available:false}, which proves nothing about this endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "interview.context = demo-provider")
class DemoProviderAttemptsTest extends PostgresTestBase {

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
    void acceptingOperationsLeavesTheDemoProviderUntouched() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Customer-Id", "customer-a");
        String body = "{\"paymentId\":\"payment-123\",\"amountMinor\":2500,\"currency\":\"USD\"}";

        http.exchange("/v1/refunds", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        http.exchange("/v1/refunds", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        JsonNode calls = JSON.readTree(http.getForObject("/actuator/demo-stripe", String.class));
        assertThat(calls.get("available").asBoolean()).isTrue();
        assertThat(calls.get("attempts").asInt()).isZero();
        assertThat(calls.get("refunds").asInt()).isZero();
    }
}
