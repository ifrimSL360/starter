package interview.executor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Environment smoke test. It proves that the application starts, Flyway applies
 * the schema, and the shipped table is present. Keep it green.
 */
@SpringBootTest
class StarterSmokeTest extends PostgresTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliesTheShippedSchema() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(applied).isNotNull();
        assertThat(applied).isGreaterThanOrEqualTo(1);

        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'refund_operation'",
                String.class);
        assertThat(columns).contains(
                "operation_id",
                "customer_id",
                "payment_id",
                "amount_minor",
                "currency",
                "status",
                "stripe_refund_id",
                "created_at",
                "updated_at");
    }
}
