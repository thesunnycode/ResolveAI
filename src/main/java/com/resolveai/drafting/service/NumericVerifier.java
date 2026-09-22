package com.resolveai.drafting.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Catches a fabricated number, amount or date before any model is asked to verify
 * anything. Doc 15 Task 14.
 *
 * <h2>Run this first — it is free and it catches the most damaging failure mode</h2>
 *
 * <p>A model inventing <i>"your refund of ₹2,499 will be credited by 24 September"</i>
 * produces a claim a customer will hold the business to, from a number the model
 * pattern-matched into plausibility rather than read anywhere. Catching that costs zero
 * LLM calls: every numeral in the claim either appears in the cited span, normalised for
 * formatting, or it does not, and that is a string comparison, not a judgement call. Run
 * before the entailment verifier so the free, certain check has first refusal on the
 * expensive, probabilistic one.
 *
 * <h2>The normalisation has to work in both directions</h2>
 *
 * <p>{@code ₹2,499} in a claim must match {@code 2499} in a span, and {@code 5-7} must
 * match {@code 5–7} (en dash). An over-strict matcher that fails to normalise correctly
 * drops <i>valid, well-grounded</i> claims and makes the whole feature look unreliable
 * for a formatting reason that has nothing to do with fabrication — which is a worse
 * failure than being slightly permissive, because it is invisible until someone notices
 * a true claim getting rejected.
 */
@Component
public class NumericVerifier {

    /**
     * Currency amounts: ₹, Rs, INR, with optional separators and decimals.
     * {@code ₹2,499.50}, {@code Rs 2499}, {@code INR 2,499}.
     */
    private static final Pattern CURRENCY = Pattern.compile(
            "(?:₹|Rs\\.?|INR)\\s*([\\d,]+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    /** A bare number with optional thousands separators and a decimal part. */
    private static final Pattern NUMBER = Pattern.compile("\\b\\d[\\d,]*(?:\\.\\d+)?\\b");

    /** A percentage: {@code 15%}. */
    private static final Pattern PERCENTAGE = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\s*%");

    /**
     * A duration range or single figure with a unit: {@code 5-7 business days},
     * {@code 24 hours}, {@code 2 minutes}. The en dash and hyphen are both accepted and
     * normalised to the same form.
     */
    private static final Pattern DURATION = Pattern.compile(
            "\\b(\\d+)\\s*[-–]\\s*(\\d+)\\s+(business\\s+)?(day|hour|minute|week)s?\\b",
            Pattern.CASE_INSENSITIVE);

    /** Dates in the formats the corpus uses: {@code 24 September}, {@code 24/09/2026}. */
    private static final Pattern DATE_WORD = Pattern.compile(
            "\\b(\\d{1,2})\\s+(January|February|March|April|May|June|July|August|September|"
            + "October|November|December)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_NUMERIC = Pattern.compile(
            "\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})\\b");

    /** Order references, invoice numbers, error codes — identifiers, not amounts. */
    private static final Pattern IDENTIFIER = Pattern.compile(
            "\\b(?:ORD|INV|TKT|UTR|ERR)[-_][A-Z0-9]{2,}\\b", Pattern.CASE_INSENSITIVE);

    public record Extracted(String raw, String normalised) {
    }

    public sealed interface Verdict {
        record Pass() implements Verdict {
        }

        record Fail(String reason) implements Verdict {
        }
    }

    /**
     * @param claimText  a candidate claim's text
     * @param citedSpans the text of every chunk the claim cites — verification passes if
     *                   each extracted value appears in <b>any</b> one of them, not
     *                   necessarily the same one
     */
    public Verdict verify(String claimText, List<String> citedSpans) {
        List<Extracted> claimed = extractAll(claimText);
        if (claimed.isEmpty()) {
            // Nothing numeric to fabricate. Not this filter's concern either way.
            return new Verdict.Pass();
        }

        String normalisedSpans = citedSpans.stream()
                .map(NumericVerifier::normalise)
                .reduce("", (a, b) -> a + " " + b);

        List<String> unsupported = new ArrayList<>();
        for (Extracted value : claimed) {
            if (!normalisedSpans.contains(value.normalised())) {
                unsupported.add(value.raw());
            }
        }

        if (unsupported.isEmpty()) {
            return new Verdict.Pass();
        }
        return new Verdict.Fail("Value" + (unsupported.size() > 1 ? "s " : " ")
                + String.join(", ", unsupported)
                + " do not appear in any cited span");
    }

    /** Every numeral-shaped thing in the text, each already normalised for comparison. */
    private List<Extracted> extractAll(String text) {
        List<Extracted> found = new ArrayList<>();
        addMatches(found, IDENTIFIER, text, m -> m.group());
        addMatches(found, CURRENCY, text, m -> m.group(1));
        addMatches(found, PERCENTAGE, text, m -> m.group(1) + "%");
        addMatches(found, DURATION, text, m -> m.group(1) + "-" + m.group(2));
        addMatches(found, DATE_WORD, text, m -> m.group(1) + " " + m.group(2).toLowerCase(Locale.ROOT));
        addMatches(found, DATE_NUMERIC, text, m -> m.group(1) + "-" + m.group(2) + "-" + m.group(3));

        // Bare numbers last, and skip any span already covered by a more specific match
        // above (a currency amount's digits, a duration's two numbers) so "2499" is not
        // separately flagged as an unsupported bare number when "₹2499" was already
        // checked as a currency amount.
        Matcher numbers = NUMBER.matcher(text);
        while (numbers.find()) {
            boolean withinAnother = found.stream()
                    .anyMatch(e -> e.raw().contains(numbers.group()));
            if (!withinAnother) {
                found.add(new Extracted(numbers.group(), normaliseNumber(numbers.group())));
            }
        }
        return found;
    }

    private void addMatches(List<Extracted> out, Pattern pattern, String text,
                            java.util.function.Function<Matcher, String> extractor) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            String normalised = normaliseNumber(extractor.apply(matcher));
            out.add(new Extracted(raw, normalised));
        }
    }

    /** Strips separators and case so {@code ₹2,499} and {@code 2499} compare equal. */
    private static String normaliseNumber(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace(",", "")
                .replace("–", "-")
                .replace(" ", "");
    }

    /** The cited-span side of the same normalisation, applied to a whole passage. */
    private static String normalise(String span) {
        return span.toLowerCase(Locale.ROOT)
                .replace(",", "")
                .replace("–", "-");
    }
}
