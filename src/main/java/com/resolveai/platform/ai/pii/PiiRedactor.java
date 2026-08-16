package com.resolveai.platform.ai.pii;

import com.resolveai.platform.tenant.TenantContext;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces personal data with placeholders before text leaves for a model, and puts it
 * back when rendering for a human who is allowed to see it.
 *
 * <h2>Determinism is a correctness requirement, not a nicety</h2>
 *
 * <p>The same input must always produce byte-identical output. Two reasons, and the
 * second is the expensive one:
 *
 * <ul>
 *   <li>The embedding cache is keyed on a hash of the <i>redacted</i> text. Placeholders
 *       that vary between runs mean the key varies, the cache never hits, and every
 *       embedding is paid for twice without anything appearing to be wrong.
 *   <li>Two tickets that differ only in a customer's name <i>should</i> produce the same
 *       redacted text, and therefore the same embedding — which is exactly what incident
 *       correlation needs in Phase 8.
 * </ul>
 *
 * <p>So numbering is assigned in <b>document order</b>, never in detection order. The
 * detectors run in whatever order is convenient; the sort by offset before numbering is
 * what makes the output stable.
 *
 * <h2>The direction of failure</h2>
 *
 * <p>A detector that half-matches is worse than one that does not match at all. Leaving
 * {@code 1111} behind after redacting {@code 4111 1111 1111} still leaks, but now it
 * looks like redaction happened and nobody checks again. {@code PiiRedactionTest} asserts
 * that no substring of any original value survives, which is the only form of the
 * assertion that catches this.
 */
@Service
public class PiiRedactor {

    private static final Logger log = LoggerFactory.getLogger(PiiRedactor.class);

    /**
     * Guillemets, because they cannot occur in ordinary ticket text.
     *
     * <p>Angle brackets or curly braces would collide with code snippets and JSON, which
     * customers paste constantly — and a placeholder that a customer can type is a
     * placeholder an attacker can forge into a ticket body to make rehydration reveal
     * somebody else's data.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("«([A-Z_]+)_(\\d+)»");

    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final Pattern orderRefPattern;

    public PiiRedactor(JdbcTemplate jdbc, PiiCipher cipher,
                       @Value("${resolveai.ai.order-ref-pattern:\\bORD-\\d{4,10}\\b}")
                       String orderRefPattern) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.orderRefPattern = Pattern.compile(orderRefPattern);
    }

    /**
     * @param redactedText     what may leave the building
     * @param placeholderCount how many substitutions were made, for the metric that shows
     *                         a detector silently stopping working
     */
    public record RedactionResult(String redactedText, int placeholderCount,
                                  Map<String, String> placeholders) {
    }

    /**
     * Redacts, and persists the mapping so the text can be rehydrated later.
     *
     * <p>Runs in the caller's transaction where there is one. The mapping and whatever
     * the caller does with the redacted text should commit together: a redacted analysis
     * saved without its mapping is permanently unreadable, and a mapping saved without
     * the analysis is personal data stored for no reason.
     */
    @Transactional
    public RedactionResult redact(Long tenantId, Long ticketId, String text) {
        RedactionResult result = substitute(tenantId, text);
        if (result.placeholderCount() > 0) {
            persist(tenantId, ticketId, result.placeholders());
        }
        return result;
    }

    /**
     * Redacts without persisting anything.
     *
     * <p>For text that has no ticket to hang a mapping off — an eval case, a knowledge
     * document, a search query. Nothing can be rehydrated afterwards, which is the right
     * trade when there is nothing to rehydrate <i>for</i>.
     */
    public RedactionResult redactWithoutMapping(Long tenantId, String text) {
        return substitute(tenantId, text);
    }

    /**
     * The substitution itself, shared by both entry points so that the persisted path
     * and the ephemeral one cannot drift apart — if they produced different placeholders
     * for the same text, an eval case and the ticket it was built from would stop
     * matching, and nothing would report it.
     */
    private RedactionResult substitute(Long tenantId, String text) {
        List<PiiDetector.Detection> detections =
                PiiDetector.detect(text, orderRefPattern, knownNames(tenantId));
        if (detections.isEmpty()) {
            return new RedactionResult(text, 0, Map.of());
        }

        // Numbered in document order. Detection order would be stable within one run and
        // different across runs whenever a detector changed — the subtle version of the
        // cache-miss bug described in the class comment.
        Map<PiiType, Integer> counters = new EnumMap<>(PiiType.class);
        Map<String, String> byValue = new HashMap<>();
        Map<String, String> placeholders = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;

        for (PiiDetector.Detection detection : detections) {
            out.append(text, cursor, detection.start());
            // The same value twice in one document gets the same placeholder. Numbering
            // them separately would hide that the customer repeated their order number,
            // which is information the model can legitimately use.
            String placeholder = byValue.computeIfAbsent(
                    detection.type() + "|" + detection.value(),
                    key -> "«" + detection.type() + "_"
                           + counters.merge(detection.type(), 1, Integer::sum) + "»");
            out.append(placeholder);
            placeholders.put(placeholder, detection.value());
            cursor = detection.end();
        }
        out.append(text, cursor, text.length());
        return new RedactionResult(out.toString(), placeholders.size(), placeholders);
    }

    /**
     * Puts the originals back, for a human who is allowed to see them.
     *
     * <p><b>Scoped to one ticket, and that is a security control.</b> A placeholder is
     * just {@code «CARD_1»} — every ticket has one. Looking the mapping up globally would
     * let a rendered analysis from ticket A reveal ticket B's card number, and the two
     * tickets need not even belong to the same customer. An unknown placeholder is left
     * exactly as it is rather than dropped: showing the token is honest, and silently
     * removing it would hide that a mapping is missing.
     */
    @Transactional(readOnly = true)
    public String rehydrate(Long ticketId, String text) {
        if (text == null || text.indexOf('«') < 0) {
            return text;
        }
        Map<String, byte[]> mappings = new HashMap<>();
        jdbc.query("SELECT placeholder, encrypted_value FROM pii_redaction_map WHERE ticket_id = ?",
                rs -> {
                    mappings.put(rs.getString("placeholder"), rs.getBytes("encrypted_value"));
                }, ticketId);

        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            byte[] encrypted = mappings.get(matcher.group());
            String replacement = encrypted == null ? matcher.group() : cipher.decrypt(encrypted);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private void persist(Long tenantId, Long ticketId, Map<String, String> placeholders) {
        List<Object[]> batch = new ArrayList<>(placeholders.size());
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            batch.add(new Object[]{tenantId, ticketId, entry.getKey(),
                    typeOf(entry.getKey()).name(), cipher.encrypt(entry.getValue())});
        }
        // ON CONFLICT DO NOTHING: re-triage redacts the same text again and produces the
        // same placeholders, by design. uq_pii_placeholder would otherwise turn the
        // second run into a constraint violation on a path where nothing is wrong.
        jdbc.batchUpdate("""
                INSERT INTO pii_redaction_map (tenant_id, ticket_id, placeholder, pii_type,
                                               encrypted_value)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (ticket_id, placeholder) DO NOTHING
                """, batch);
        log.debug("Redacted {} value(s) on ticket {}", placeholders.size(), ticketId);
    }

    private static PiiType typeOf(String placeholder) {
        Matcher matcher = PLACEHOLDER.matcher(placeholder);
        if (matcher.matches()) {
            return PiiType.valueOf(matcher.group(1));
        }
        throw new IllegalArgumentException("Not a placeholder: " + placeholder);
    }

    /**
     * Names of people in this tenant.
     *
     * <p>Read per call rather than cached: the list changes when a user is added, and a
     * stale cache here means a real person's name travelling to a third party. That is
     * the wrong side to be wrong on, and the query is a single indexed scan of a table
     * with tens of rows per tenant.
     */
    private List<String> knownNames(Long tenantId) {
        Long effective = tenantId != null ? tenantId
                : (TenantContext.isSet() ? TenantContext.getRequired() : null);
        if (effective == null) {
            return List.of();
        }
        return jdbc.queryForList(
                "SELECT full_name FROM app_user WHERE tenant_id = ? AND deleted_at IS NULL",
                String.class, effective);
    }
}
