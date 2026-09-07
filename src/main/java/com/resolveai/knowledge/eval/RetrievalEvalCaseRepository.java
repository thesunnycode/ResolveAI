package com.resolveai.knowledge.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The 70 {@code (query, relevantDocumentTitles, group)} cases from
 * {@code src/main/resources/eval/retrieval-cases.json}.
 *
 * <p>Cases name documents by <b>title</b>, not chunk id. A chunk id is a database
 * identity, regenerated on every reseed; a title is the stable thing an eval case can
 * actually commit to. {@link RetrievalEvalRunner} resolves titles to whatever chunk ids
 * currently exist at run time, which is also what makes the suite survive a corpus
 * reindex without every case needing to be rewritten.
 */
@Repository
public class RetrievalEvalCaseRepository {

    private static final String RESOURCE = "eval/retrieval-cases.json";

    private final ObjectMapper objectMapper;

    public RetrievalEvalCaseRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record RawCase(String name, String group, String query,
                          List<String> relevantDocumentTitles) {
    }

    @SuppressWarnings("unchecked")
    public List<RawCase> readAll() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(in,
                    new TypeReference<Map<String, Object>>() { });
            List<Map<String, Object>> raw = (List<Map<String, Object>>) root.get("cases");
            return raw.stream()
                    .map(c -> new RawCase((String) c.get("name"), (String) c.get("group"),
                            (String) c.get("query"),
                            (List<String>) c.get("relevantDocumentTitles")))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }
}
