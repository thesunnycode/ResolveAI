package com.resolveai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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
@Testcontainers
public abstract class IntegrationTestBase {

    @Container
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
    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    @Autowired
    protected TestRestTemplate rest;
}
