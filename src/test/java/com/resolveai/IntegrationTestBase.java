package com.resolveai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for every integration test: a real PostgreSQL with pgvector, a real Redis, and
 * the real Flyway migrations.
 *
 * <p><b>Both containers are {@code static}, and that is not a style preference.</b> A
 * non-static {@code @Container} starts a fresh Postgres for every test class. With the
 * thirty-odd test classes this project ends up with, that is the difference between a suite
 * you run on every save and one you run once a day — and a suite you do not run is a suite
 * that does not catch anything, which wastes the entire investment in writing it.
 *
 * <p><b>The Postgres image is {@code pgvector/pgvector:pg16}, not {@code postgres:16}.</b>
 * Plain Postgres has no {@code vector} type, so {@code V1} fails on
 * {@code CREATE EXTENSION vector} and every test in the project errors during context
 * startup with a message that does not obviously point at the image tag.
 *
 * <p><b>Flyway runs, and {@code ddl-auto} is {@code validate}.</b> Tests exercise the same
 * migrations production runs. Letting Hibernate generate the schema instead would hide
 * exactly the defects an integration test exists to find: a partial index whose
 * {@code WHERE} clause was dropped, a trigger that was never created, a default only the
 * migration sets.
 *
 * <h2>Why there is no {@code @Testcontainers} annotation</h2>
 *
 * <p>This is the <b>singleton container</b> pattern: the containers start once in a static
 * initialiser and are never explicitly stopped, so they live for the whole JVM and Ryuk
 * reaps them when the build exits.
 *
 * <p>The obvious alternative - {@code @Testcontainers} with {@code @Container} on a static
 * field - <b>does not survive a suite</b>, and it fails in a way that looks like something
 * else entirely. The JUnit extension ties a static container to the <i>declaring test
 * class</i>, so the containers stop when the first test class finishes and every class
 * after it fails with {@code Connection is not available, request timed out} against an
 * empty pool. That message reads like pool exhaustion or a connection leak and sends you to
 * read Hikari settings; the real cause is that the database is no longer running. It cost a
 * detour here, which is why it is written down.
 *
 * <p>Three coordinates here moved under Boot 4 / Testcontainers 2 and are worth naming,
 * because each one fails at compile time with a message that points at the symbol rather
 * than at the reason: {@code TestRestTemplate} moved to
 * {@code org.springframework.boot.resttestclient}, {@code @ServiceConnection} needs the
 * {@code spring-boot-testcontainers} dependency (transitive from nothing), and
 * {@code org.testcontainers.postgresql.PostgreSQLContainer} is no longer generic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Boot 4 no longer contributes a TestRestTemplate implicitly for a RANDOM_PORT test; the
// bean only exists when this annotation asks for it. Without it the context starts and the
// failure is a NoSuchBeanDefinitionException on the field, which does not obviously read as
// "add an annotation".
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    // pgvector's image is a Postgres image; Testcontainers will not accept it
                    // as one without being told, because the repository name differs.
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("resolveai")
                    .withUsername("resolveai")
                    .withPassword("resolveai_test")
                    // Runs before the first connection, exactly as ops/postgres-init does for
                    // docker compose. V1 creates the extensions too, but relying only on that
                    // would mean the test path and the compose path differ in how the
                    // database comes to have them - and then one of them can rot unnoticed.
                    .withInitScript("testcontainers-init.sql");

    /**
     * Redis as a plain {@code GenericContainer} with a named {@code @ServiceConnection}.
     *
     * <p>Testcontainers 2.0.5 ships no Redis module, and {@code com.redis:testcontainers-redis}
     * targets the 1.x API. Boot resolves the connection details from the name, so this costs
     * two extra lines and removes a third-party dependency from the build.
     */
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    static {
        // The test HTTP client (Reactor Netty, via TestRestTemplate) keeps pooled
        // connections with no idle limit. Tomcat drops a kept-alive connection after 20s,
        // so a connection idle longer than that may already be dead on the server side -
        // and in a long suite, where a class can go minutes without an HTTP call, reusing
        // one made the next request (usually a login) hang for 20s and fail with
        // "Connection reset". Evicting after 10s, below the server's timeout, is the usual
        // client/server keep-alive rule. Set before any client exists: Reactor reads it
        // once, when its default connection provider is created.
        System.setProperty("reactor.netty.pool.maxIdleTime", "10000");

        // Started once for the JVM. Starting an already-started container is a no-op, so
        // this is safe however many subclasses exist. Nothing stops them: Ryuk removes them
        // when the build process exits, and stopping them per test class is precisely the
        // bug described above.
        POSTGRES.start();
        REDIS.start();
    }

    @Autowired
    protected TestRestTemplate rest;

    /**
     * The provider circuit breaker, closed again before every test.
     *
     * <h2>Why this lives in the base class</h2>
     *
     * <p>The Spring context is shared across every test class in the suite, and so is
     * the {@code CircuitBreakerRegistry} inside it. A class that deliberately fails the
     * provider — and three of them now do, because that is the interesting half of an
     * AI integration — leaves the breaker {@code OPEN}, and the next class to run gets
     * its calls refused locally with no network request. The symptom is a test that
     * passes alone and fails in the suite, reporting something entirely unrelated: a
     * load-distribution test asserting 37 assignments instead of 50, for instance,
     * because thirteen triages were refused by a breaker a different file opened.
     *
     * <p>Resetting per class as it came up would work and would be forgotten by the
     * next person to write a failure test, so it is done once, here, for everyone.
     *
     * <p>{@code required = false} because a slice test without the AI configuration has
     * no registry, and this should not be the reason such a test cannot start.
     */
    @Autowired(required = false)
    private io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry breakerRegistry;

    @org.junit.jupiter.api.BeforeEach
    void resetCircuitBreakers() {
        if (breakerRegistry != null) {
            breakerRegistry.getAllCircuitBreakers().forEach(
                    io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
        }
    }
}
