package com.resolveai.eval.web;

import com.resolveai.common.security.IsAdmin;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Accuracy over time, by prompt version.
 *
 * <h2>Why a list rather than a number</h2>
 *
 * <p>"Our classifier is 91% accurate" is not a useful sentence on its own — accurate
 * against which suite, on which prompt, when? The value is in the sequence: the run
 * before a prompt change and the run after it, side by side, with the version labels
 * attached. That is what makes a prompt edit an engineering change rather than a guess.
 *
 * <p>{@code ADMIN} only, and not tenant-scoped, because {@code eval_run} is not tenant
 * data: the suite measures the prompt, which is shared, and a per-tenant accuracy figure
 * would be measuring which tickets happened to arrive rather than how well the
 * classifier reads them.
 */
@RestController
@RequestMapping("/api/v1/admin/eval")
public class EvalController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EvalController(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/runs")
    @IsAdmin
    public List<Map<String, Object>> runs(
            @RequestParam(required = false, defaultValue = "CLASSIFICATION") String suite,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return jdbc.query("""
                SELECT r.id, r.suite, r.model_id, r.passed, r.metrics, r.started_at,
                       r.finished_at,
                       p.name AS prompt_name, p.version AS prompt_version
                  FROM eval_run r
                  LEFT JOIN prompt_version p ON p.id = r.prompt_version_id
                 WHERE r.suite = ?
                 ORDER BY r.started_at DESC, r.id DESC
                 LIMIT ?
                """,
                (rs, rowNum) -> Map.of(
                        "id", rs.getLong("id"),
                        "suite", rs.getString("suite"),
                        "promptVersion", rs.getString("prompt_name") == null ? "unknown"
                                : rs.getString("prompt_name") + "@" + rs.getInt("prompt_version"),
                        "modelId", rs.getString("model_id"),
                        "passed", rs.getBoolean("passed"),
                        "metrics", objectMapper.readValue(rs.getString("metrics"),
                                new TypeReference<Map<String, Object>>() { }),
                        "startedAt", rs.getObject("started_at", OffsetDateTime.class),
                        "finishedAt", rs.getObject("finished_at", OffsetDateTime.class)),
                suite, Math.clamp(limit, 1, 100));
    }
}
