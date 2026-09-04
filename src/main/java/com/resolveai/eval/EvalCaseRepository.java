package com.resolveai.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the labelled corpus into {@code eval_case}, and reads it back.
 *
 * <h2>Why the cases live in a resource file and not in a migration</h2>
 *
 * <p>A migration is immutable once applied — which is exactly right for a prompt, and
 * exactly wrong for an eval suite. Cases get added every time an agent overrides a
 * priority in a way that turns out to be a good label, and every time an incident
 * reveals a ticket the classifier read wrongly. That is a file that changes, reviewably,
 * in ordinary pull requests, where a reviewer can see "this label changed" in the diff.
 *
 * <p>It is also not tenant data. The suite measures the prompt, and a prompt that scores
 * differently per tenant is not being measured at all — so there is no {@code tenant_id}
 * on {@code eval_case}, and the seeder runs once for the installation.
 */
@Repository
public class EvalCaseRepository {

    private static final Logger log = LoggerFactory.getLogger(EvalCaseRepository.class);

    private static final String RESOURCE = "eval/classification-cases.json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EvalCaseRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Upserts every case in the resource file. Idempotent.
     *
     * <p>{@code ON CONFLICT … DO UPDATE} rather than {@code DO NOTHING}: a corrected
     * label has to reach the database, and a suite that silently kept the old one would
     * report a regression that was actually a fix.
     */
    @Transactional
    public int seed() {
        List<Map<String, Object>> cases = readResource();
        for (Map<String, Object> raw : cases) {
            jdbc.update("""
                    INSERT INTO eval_case (suite, name, input_fixture, expected, source)
                    VALUES ('CLASSIFICATION', ?, CAST(? AS jsonb), CAST(? AS jsonb),
                            'HAND_LABELLED')
                    ON CONFLICT (suite, name) DO UPDATE
                       SET input_fixture = EXCLUDED.input_fixture,
                           expected = EXCLUDED.expected
                    """,
                    raw.get("name"),
                    objectMapper.writeValueAsString(Map.of(
                            "subject", raw.get("subject"),
                            "body", raw.get("body"))),
                    objectMapper.writeValueAsString(Map.of(
                            "category", raw.get("expectedCategory"),
                            "team", raw.get("expectedTeam"))));
        }
        log.info("Seeded {} CLASSIFICATION eval cases", cases.size());
        return cases.size();
    }

    /** The active cases, in a stable order so two runs are comparable line by line. */
    public List<EvalCase> active(String suite) {
        return jdbc.query("""
                SELECT id, name, input_fixture, expected FROM eval_case
                 WHERE suite = ? AND is_active
                 ORDER BY name
                """,
                (rs, rowNum) -> {
                    Map<String, String> fixture = readMap(rs.getString("input_fixture"));
                    Map<String, String> expected = readMap(rs.getString("expected"));
                    return new EvalCase(rs.getLong("id"), rs.getString("name"),
                            fixture.get("subject"), fixture.get("body"),
                            expected.get("category"), expected.get("team"));
                }, suite);
    }

    /** The raw file, for a test that wants the fixtures without a database. */
    public List<Map<String, Object>> readResource() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(in,
                    new TypeReference<Map<String, Object>>() { });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cases = (List<Map<String, Object>>) root.get("cases");
            return cases;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }

    private Map<String, String> readMap(String json) {
        return objectMapper.readValue(json, new TypeReference<Map<String, String>>() { });
    }
}
