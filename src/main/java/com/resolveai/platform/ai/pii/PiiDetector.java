package com.resolveai.platform.ai.pii;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds personal data in a block of text. Pure, stateless, and unit-testable without a
 * database — which is the point, because this is the class whose correctness matters most
 * and the last one that should need a Spring context to exercise.
 *
 * <h2>Overlaps are resolved by specificity, then by length</h2>
 *
 * <p>Real text produces overlapping matches constantly. {@code ORD-9876543210} contains
 * a ten-digit string that looks exactly like an Indian mobile number;
 * {@code 4111 1111 1111 1111} contains several. If both detectors are allowed to fire,
 * the result is {@code ORD-«PHONE_1»} — an order reference the model can no longer read
 * and a "redaction" that protected nothing. Worse is the partial case: redacting
 * {@code 4111 1111 1111} and leaving a trailing {@code 1111} in place <b>looks like it
 * worked</b>, which is the most dangerous outcome available.
 *
 * <p>So every detector runs, all candidates are collected with their offsets, and a
 * single pass keeps the winner: earliest start, then most specific type, then longest.
 * Nothing overlapping a kept match survives.
 */
public final class PiiDetector {

    /**
     * A found piece of personal data.
     *
     * @param start inclusive, in the original string
     * @param end   exclusive
     */
    public record Detection(int start, int end, PiiType type, String value) {
        int length() {
            return end - start;
        }

        boolean overlaps(Detection other) {
            return start < other.end && other.start < end;
        }
    }

    // ── Patterns ────────────────────────────────────────────────────────────

    /** PAN: five letters, four digits, one letter. Unambiguous enough to run first. */
    private static final Pattern PAN = Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b");

    /**
     * Aadhaar: twelve digits, optionally in groups of four.
     *
     * <p><b>The lookarounds are the whole pattern.</b> Without them it matches the first
     * twelve digits of a sixteen-digit card — {@code 4111 1111 1111} out of
     * {@code 4111 1111 1111 1111} — and leaves the trailing {@code 1111} sitting in the
     * text. A word boundary does not help, because there genuinely is one: the next
     * character is a space. The guard has to be "not adjacent to more digits", in both
     * directions, separators included.
     *
     * <p>That bug existed for about ten minutes and was caught by the no-leakage test,
     * which is exactly the case that test exists for — the output looked redacted.
     */
    private static final Pattern AADHAAR = Pattern.compile(
            "(?<!\\d)(?<!\\d )(?<!\\d-)\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?![ -]?\\d)");

    /** 13–19 digits with optional separators. Luhn decides whether it is really a card. */
    private static final Pattern CARD_SHAPED =
            Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

    private static final Pattern EMAIL =
            Pattern.compile("\\b[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b");

    /**
     * Phone numbers: {@code +91} with or without spaces, and bare ten-digit Indian
     * mobiles, which always start 6–9. Requiring that first digit is what stops every
     * ten-digit order number in the corpus being read as a phone number.
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+\\d{1,3}[ -]?)?(?:\\(\\d{2,4}\\)[ -]?)?\\b[6-9]\\d{4}[ -]?\\d{5}\\b"
            + "|\\+\\d{1,3}[ -]?\\d[\\d -]{6,12}\\d");

    /**
     * <b>A dotted quad is redacted even when it is a version number.</b>
     * {@code 10.24.3.1} is a valid address and a plausible build number, and nothing in
     * the shape tells them apart. The two ways of being wrong are not equal: redacting a
     * version string costs the model one detail, while missing a customer's IP address
     * sends it to a third party. So this matches, and the placeholder keeps its type so
     * a reader can see what was assumed.
     */
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![.\\d])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}"
            + "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?![.\\d])");

    /** Deliberately narrow: enough to catch a real address, not every colon-separated id. */
    private static final Pattern IPV6 = Pattern.compile(
            "\\b(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}\\b"
            + "|\\b(?:[0-9A-Fa-f]{1,4}:){2,6}:[0-9A-Fa-f]{1,4}\\b");

    private PiiDetector() {
    }

    /**
     * Every piece of personal data in {@code text}, ordered by position and
     * de-overlapped.
     *
     * @param orderRefPattern the tenant's order-reference shape, or null
     * @param knownNames      user names in this tenant, matched exactly. A name list is
     *                        the only reliable way to catch {@code PERSON} without a
     *                        model: name detection by heuristic either misses half of
     *                        them or redacts every capitalised word, and "Payments team"
     *                        becoming {@code «PERSON_1» team} is its own kind of damage.
     */
    public static List<Detection> detect(String text, Pattern orderRefPattern,
                                         Collection<String> knownNames) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<Detection> candidates = new ArrayList<>();
        collect(candidates, text, PAN, PiiType.GOV_ID);
        collect(candidates, text, AADHAAR, PiiType.GOV_ID);
        collectCards(candidates, text);
        if (orderRefPattern != null) {
            collect(candidates, text, orderRefPattern, PiiType.ORDER_REF);
        }
        collect(candidates, text, EMAIL, PiiType.EMAIL);
        collect(candidates, text, PHONE, PiiType.PHONE);
        collect(candidates, text, IPV4, PiiType.IP);
        collect(candidates, text, IPV6, PiiType.IP);
        collectNames(candidates, text, knownNames);

        return resolve(candidates);
    }

    /**
     * Keeps the best candidate at each position and drops everything overlapping it.
     *
     * <p>Sorted by start, then by the enum's own order (most specific first), then by
     * length descending. The type comparison is what makes {@code GOV_ID} beat
     * {@code PHONE} on a twelve-digit string that both match, and the length comparison
     * settles two detectors of the same specificity — the longer match is always the
     * safer one, because a shorter one leaves a fragment of the original in place.
     */
    private static List<Detection> resolve(List<Detection> candidates) {
        candidates.sort(Comparator
                .comparingInt(Detection::start)
                .thenComparing(d -> d.type().ordinal())
                .thenComparing(Comparator.comparingInt(Detection::length).reversed()));

        List<Detection> kept = new ArrayList<>();
        for (Detection candidate : candidates) {
            boolean clashes = kept.stream().anyMatch(candidate::overlaps);
            if (!clashes) {
                kept.add(candidate);
            }
        }
        // Back into document order: the numbering in PiiRedactor depends on it, and
        // "document order" is the only ordering that is stable across runs.
        kept.sort(Comparator.comparingInt(Detection::start));
        return kept;
    }

    private static void collect(List<Detection> into, String text, Pattern pattern,
                                PiiType type) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            into.add(new Detection(matcher.start(), matcher.end(), type, matcher.group()));
        }
    }

    /**
     * Card numbers, <b>Luhn-checked</b>.
     *
     * <p>Without the check, this detector eats order numbers, invoice references,
     * transaction ids and UTRs — every long numeric string in a support ticket about
     * money, which is most of them. The model then receives {@code «CARD_3»} where the
     * customer wrote their order number and loses the one detail that would have let it
     * classify the ticket. Luhn costs a dozen lines and turns a noisy detector into a
     * precise one.
     */
    private static void collectCards(List<Detection> into, String text) {
        Matcher matcher = CARD_SHAPED.matcher(text);
        while (matcher.find()) {
            String digitsOnly = matcher.group().replaceAll("[^0-9]", "");
            if (digitsOnly.length() >= 13 && digitsOnly.length() <= 19 && Luhn.isValid(digitsOnly)) {
                into.add(new Detection(matcher.start(), matcher.end(), PiiType.CARD,
                        matcher.group()));
            }
        }
    }

    /**
     * Known names, matched case-insensitively on whole words.
     *
     * <p>Longest first, so "Priya Raman" is redacted as one person rather than as
     * "«PERSON_1» «PERSON_2»" — and so that a tenant containing both "Priya" and
     * "Priya Raman" does not have the shorter name shadow the longer.
     */
    private static void collectNames(List<Detection> into, String text,
                                     Collection<String> knownNames) {
        if (knownNames == null) {
            return;
        }
        knownNames.stream()
                .filter(name -> name != null && name.length() >= 3)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .forEach(name -> {
                    Matcher matcher = Pattern
                            .compile("\\b" + Pattern.quote(name) + "\\b", Pattern.CASE_INSENSITIVE)
                            .matcher(text);
                    while (matcher.find()) {
                        into.add(new Detection(matcher.start(), matcher.end(), PiiType.PERSON,
                                matcher.group()));
                    }
                });
    }
}
