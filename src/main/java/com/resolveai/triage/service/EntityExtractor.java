package com.resolveai.triage.service;

import com.resolveai.ticketing.domain.TicketEntityType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pulls technical entities out of a ticket's text, deterministically.
 *
 * <h2>This is deliberately not the LLM's job</h2>
 *
 * <p>{@code TriageSignals.extractedEntities()} is a separate, advisory field the model also
 * produces — do not use it here. Extraction here feeds {@code TicketClusterer}'s
 * shared-entity boost, which feeds {@code CorrelationGate}. The entire argument of Phase 8
 * is that <i>no model has a vote in whether an incident exists</i>; if the entities behind
 * the clustering boost came from a model, that sentence would be false. Regex is not a
 * downgrade here — it is the point.
 *
 * <h2>False positives are the expensive failure mode</h2>
 *
 * <p>Every spurious entity inflates the boost between two unrelated tickets and makes them
 * look correlated. {@code HTTP_STATUS} is the riskiest type for exactly this reason — bare
 * three-digit numbers are common in ordinary prose — so it alone requires a nearby context
 * word rather than a bare pattern match.
 */
@Component
public class EntityExtractor {

    /** {@code ERR_PAYMENT_TIMEOUT}, {@code ERR_5XX}. */
    private static final Pattern ERR_CODE = Pattern.compile("\\bERR_[A-Z0-9_]+\\b");

    /** {@code E404}, {@code E12345}. */
    private static final Pattern E_CODE = Pattern.compile("\\bE\\d{3,5}\\b");

    /** {@code NullPointerException}, {@code TimeoutError} — a capitalised word ending in
     * {@code Exception} or {@code Error}, optionally dotted like a fully-qualified name. */
    private static final Pattern JAVA_EXCEPTION = Pattern.compile(
            "\\b(?:[A-Za-z][A-Za-z0-9]*\\.)*[A-Z][A-Za-z0-9]*(?:Exception|Error)\\b");

    /** A three-digit HTTP status, 4xx or 5xx. Context is checked separately. */
    private static final Pattern HTTP_STATUS = Pattern.compile("\\b([45]\\d{2})\\b");

    private static final Set<String> HTTP_CONTEXT_WORDS = Set.of(
            "error", "status", "code", "response", "returned", "returns", "got", "gives",
            "http", "gateway", "server", "service", "request", "failed", "failure",
            "throwing", "throws");

    /** Excluded even with nearby context words — "404 pages" is never an HTTP status. */
    private static final Set<String> HTTP_FALSE_FRIEND_FOLLOWERS = Set.of(
            "page", "pages", "km", "miles", "rupees", "dollars", "%");

    /** Cloud-style region codes: {@code ap-south-1}, {@code us-east-2}. */
    private static final Pattern REGION_PATTERN = Pattern.compile(
            "\\b[a-z]{2}(?:-[a-z]+){1,2}-\\d\\b");

    /** {@code 2.14.0}, {@code v3.1}, in a version-like context. */
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "\\bv?(\\d+\\.\\d+(?:\\.\\d+)?)\\b");
    private static final Set<String> VERSION_CONTEXT_WORDS = Set.of(
            "version", "app", "build", "release", "v", "update", "upgraded", "installed");

    private static final Set<String> PAYMENT_METHODS = Set.of(
            "upi", "card", "netbanking", "net banking", "wallet", "neft", "imps", "rtgs",
            "debit card", "credit card");

    private final Set<String> serviceNames;
    private final Set<String> regionNames;

    public EntityExtractor(
            @Value("#{'${resolveai.entities.services:payment-service,auth-service,"
                    + "checkout-service,notification-service,billing-service,"
                    + "webhook-service,order-service,inventory-service}'}"
                    + ".split(',')}") List<String> serviceNames,
            @Value("#{'${resolveai.entities.regions:ap-south-1,us-east-1,us-east-2,"
                    + "us-west-2,eu-west-1,eu-central-1}'}"
                    + ".split(',')}") List<String> regionNames) {
        this.serviceNames = lower(serviceNames);
        this.regionNames = lower(regionNames);
    }

    private static Set<String> lower(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            out.add(v.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * @param type  the kind of entity found
     * @param value the surface form, lower-cased for everything except error codes (whose
     *              case is part of their identity — {@code ERR_PAY_TIMEOUT} and
     *              {@code err_pay_timeout} name the same code, and lower-casing loses
     *              nothing; the case is kept only so a code copied out of the UI still
     *              reads naturally)
     */
    public record ExtractedEntity(TicketEntityType type, String value) {
    }

    public Set<ExtractedEntity> extract(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<ExtractedEntity> out = new LinkedHashSet<>();
        extractErrorCodes(text, out);
        extractHttpStatuses(text, out);
        extractServices(text, out);
        extractRegions(text, out);
        extractPaymentMethods(text, out);
        extractVersions(text, out);
        return out;
    }

    private void extractErrorCodes(String text, Set<ExtractedEntity> out) {
        addAll(out, ERR_CODE, text, TicketEntityType.ERROR_CODE, false);
        addAll(out, E_CODE, text, TicketEntityType.ERROR_CODE, false);
        addAll(out, JAVA_EXCEPTION, text, TicketEntityType.ERROR_CODE, false);
    }

    private void extractHttpStatuses(String text, Set<ExtractedEntity> out) {
        Matcher m = HTTP_STATUS.matcher(text);
        while (m.find()) {
            if (hasHttpContext(text, m.start(), m.end())) {
                out.add(new ExtractedEntity(TicketEntityType.HTTP_STATUS, m.group(1)));
            }
        }
    }

    /**
     * True when a status-ish word sits within 25 characters either side, and no
     * false-friend word (like "pages") immediately follows the number.
     */
    private boolean hasHttpContext(String text, int start, int end) {
        int windowStart = Math.max(0, start - 25);
        int windowEnd = Math.min(text.length(), end + 25);
        String before = text.substring(windowStart, start).toLowerCase(Locale.ROOT);
        String after = text.substring(end, windowEnd).toLowerCase(Locale.ROOT);

        String firstWordAfter = after.trim().split("[\\s,.!?]+", 2)[0];
        if (HTTP_FALSE_FRIEND_FOLLOWERS.contains(firstWordAfter)) {
            return false;
        }
        for (String word : HTTP_CONTEXT_WORDS) {
            if (before.contains(word) || after.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private void extractServices(String text, Set<ExtractedEntity> out) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String service : serviceNames) {
            if (containsWord(lower, service)) {
                out.add(new ExtractedEntity(TicketEntityType.SERVICE, service));
            }
        }
    }

    private void extractRegions(String text, Set<ExtractedEntity> out) {
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher m = REGION_PATTERN.matcher(lower);
        while (m.find()) {
            out.add(new ExtractedEntity(TicketEntityType.REGION, m.group()));
        }
        for (String region : regionNames) {
            if (containsWord(lower, region)) {
                out.add(new ExtractedEntity(TicketEntityType.REGION, region));
            }
        }
    }

    private void extractPaymentMethods(String text, Set<ExtractedEntity> out) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String method : PAYMENT_METHODS) {
            if (containsWord(lower, method)) {
                // Normalise "credit card"/"debit card" to the shared "card" value so a
                // UPI-and-card storm's boost is not split across near-duplicate labels.
                String value = method.contains("card") ? "card" : method.replace(" ", "");
                out.add(new ExtractedEntity(TicketEntityType.PAYMENT_METHOD, value));
            }
        }
    }

    private void extractVersions(String text, Set<ExtractedEntity> out) {
        Matcher m = VERSION_PATTERN.matcher(text);
        while (m.find()) {
            int windowStart = Math.max(0, m.start() - 20);
            String before = text.substring(windowStart, m.start()).toLowerCase(Locale.ROOT);
            boolean hasContext = VERSION_CONTEXT_WORDS.stream().anyMatch(before::contains);
            if (hasContext) {
                out.add(new ExtractedEntity(TicketEntityType.APP_VERSION, m.group(1)));
            }
        }
    }

    /** A whole-word / whole-phrase containment check — "card" must not match "discard". */
    private static boolean containsWord(String haystackLower, String needleLower) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(needleLower) + "\\b");
        return p.matcher(haystackLower).find();
    }

    private static void addAll(Set<ExtractedEntity> out, Pattern pattern, String text,
                               TicketEntityType type, boolean lowerCase) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String value = m.group();
            out.add(new ExtractedEntity(type, lowerCase ? value.toLowerCase(Locale.ROOT) : value));
        }
    }
}
