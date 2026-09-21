package interview.executor;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts one PostgreSQL container for the whole test JVM and points Spring at it.
 *
 * <p>Extend it from a {@code @SpringBootTest} to get a real PostgreSQL database
 * instead of an in-memory substitute.
 *
 * <pre>
 * {@code @SpringBootTest}
 * class RefundServiceTest extends PostgresTestBase {
 *     // @Autowired JdbcTemplate, RefundService, ...
 * }
 * </pre>
 */
public abstract class PostgresTestBase {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        // docker-java defaults to a Docker API version that Docker Engine 29
        // rejects with HTTP 400. 1.41 is accepted by Docker Engine 20.10 and
        // later. Override with -Dapi.version=... if your Docker needs another.
        System.setProperty("api.version", System.getProperty("api.version", "1.41"));
        POSTGRES.start();
    }

    /**
     * The shared container, for tests that need a plain JDBC connection or a
     * connection of their own.
     */
    protected static PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
