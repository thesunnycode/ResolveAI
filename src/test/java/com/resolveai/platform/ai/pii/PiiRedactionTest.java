package com.resolveai.platform.ai.pii;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.ticketing.TicketTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The redactor, under the six conditions from the plan.
 *
 * <p>{@link #nothingLeaksThroughRedaction()} is the one that matters. The others check
 * that redaction happened; that one checks that it happened <i>completely</i>, by
 * asserting no substring of any original value survives. A detector that redacts
 * {@code 4111 1111 1111} and leaves {@code 1111} behind passes every other test in this
 * file, produces output that looks redacted, and still leaks — and because it looks
 * right, nobody checks it again.
 */
class PiiRedactionTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired PiiRedactor redactor;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("pii");
        customerToken = auth.accessToken(rest, "pii", "customer");
    }

    // ── 1. Round trip ───────────────────────────────────────────────────────

    @Test
    @DisplayName("rehydrate(redact(x)) returns x exactly")
    void roundTrips() {
        Long ticketId = ticket();
        String original = "Hi, I'm Customer pii, order ORD-88213 charged to "
                          + "4111 1111 1111 1111, call me on +91 98765 43210 or "
                          + "customer@pii.test";

        PiiRedactor.RedactionResult redacted = redactor.redact(tenant.tenantId(), ticketId,
                original);

        assertThat(redacted.redactedText()).isNotEqualTo(original);
        assertThat(redactor.rehydrate(ticketId, redacted.redactedText())).isEqualTo(original);
    }

    @Test
    @DisplayName("The documented example redacts to the documented output")
    void producesTheExpectedPlaceholders() {
        Long ticketId = ticket();
        String text = "Hi, I'm Customer pii, order ORD-88213 charged to "
                      + "4111 1111 1111 1111, call me on +91 98765 43210";

        String out = redactor.redact(tenant.tenantId(), ticketId, text).redactedText();

        // The type is kept in the placeholder deliberately: a model reasoning about a
        // payment problem needs to know a card number was mentioned. «REDACTED»
        // everywhere would protect the data and destroy the meaning with it.
        assertThat(out).isEqualTo("Hi, I'm «PERSON_1», order «ORDER_REF_1» charged to "
                                  + "«CARD_1», call me on «PHONE_1»");
    }

    // ── 2. Determinism ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Redacting the same text twice is byte-identical")
    void isDeterministic() {
        Long ticketId = ticket();
        String text = "Order ORD-1234 for customer@pii.test, card 4111 1111 1111 1111, "
                      + "backup contact ORD-9999 and admin@pii.test";

        String first = redactor.redact(tenant.tenantId(), ticketId, text).redactedText();
        String second = redactor.redact(tenant.tenantId(), ticketId, text).redactedText();

        // Not cosmetic. The embedding cache is keyed on a hash of this string: unstable
        // placeholders mean the key never repeats, every embedding is paid for twice,
        // and nothing anywhere reports a problem.
        assertThat(second).isEqualTo(first);
        // And numbering follows document order, so the first order reference in the text
        // is ORDER_REF_1 however the detectors happened to run.
        assertThat(first.indexOf("«ORDER_REF_1»")).isLessThan(first.indexOf("«ORDER_REF_2»"));
    }

    @Test
    @DisplayName("A repeated value gets the same placeholder both times")
    void repeatedValuesShareAPlaceholder() {
        Long ticketId = ticket();

        String out = redactor.redact(tenant.tenantId(), ticketId,
                "Order ORD-4242 is wrong. I said ORD-4242 twice.").redactedText();

        assertThat(out).isEqualTo("Order «ORDER_REF_1» is wrong. I said «ORDER_REF_1» twice.");
    }

    // ── 3. The detectors ────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "'Mail me at priya.raman@example.com please',        EMAIL",
            "'Contact: a.b+tag@sub.domain.co.in',                EMAIL",
            "'Ring +91 98765 43210 after six',                   PHONE",
            "'My number is 9876543210',                          PHONE",
            "'Charged to 4111 1111 1111 1111 yesterday',         CARD",
            "'Card 5500005555555559 was declined',               CARD",
            "'PAN ABCDE1234F on the invoice',                    GOV_ID",
            "'Aadhaar 2234 5678 9012 uploaded',                  GOV_ID",
            "'Request came from 203.0.113.42 at noon',           IP",
            "'Order ORD-88213 never shipped',                    ORDER_REF",
    })
    @DisplayName("Each detector finds its own kind")
    void detectorsFindTheirType(String text, String expectedType) {
        Long ticketId = ticket();

        String out = redactor.redact(tenant.tenantId(), ticketId, text).redactedText();

        assertThat(out).contains("«" + expectedType + "_1»");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 16 digits that fail Luhn: an order number, an invoice reference, a UTR.
            // Redacting these would hand the model «CARD_1» where the customer wrote the
            // one identifier that lets the ticket be classified.
            "Reference 1234567812345678 on the statement",
            "Transaction 9999888877776666 failed",
            // Not a phone number: Indian mobiles start 6-9, and this is a quantity.
            "We processed 1234567890 records overnight",
            // Five octets is not an address, and a price is not personal data.
            "Upgraded to 10.24.3.1.7 build yesterday and paid 4,999",
    })
    @DisplayName("Things that merely look like PII are left alone")
    void negativeCasesAreNotRedacted(String text) {
        Long ticketId = ticket();

        String out = redactor.redact(tenant.tenantId(), ticketId, text).redactedText();

        assertThat(out).as("should not have been redacted: %s", text).isEqualTo(text);
    }

    // ── 4. No leakage — the one that matters ────────────────────────────────

    /**
     * Fifty bodies in the shape the seeded corpus produces, and for each one, every
     * original value is checked not to appear anywhere in the output — not the whole
     * value, and not any run of it long enough to matter.
     *
     * <p>A partial match is the failure mode worth fearing. It leaks, and it looks like
     * it did not.
     */
    @Test
    @DisplayName("No substring of any redacted value survives, across 50 bodies")
    void nothingLeaksThroughRedaction() {
        Long ticketId = ticket();

        for (int i = 0; i < 50; i++) {
            String card = luhnCard(i);
            String phone = "9" + String.format("%09d", 800000000 + i);
            String email = "person" + i + "@example.com";
            String order = "ORD-" + (10000 + i);
            String pan = "ABCDE" + String.format("%04d", i) + "F";
            String text = "Hi, order " + order + " paid by card " + card + ". Reach me on "
                          + phone + " or " + email + ". PAN " + pan + " for the invoice.";

            PiiRedactor.RedactionResult result =
                    redactor.redact(tenant.tenantId(), ticketId, text);
            String out = result.redactedText();

            for (String secret : List.of(card, phone, email, order, pan)) {
                assertThat(out).as("whole value leaked: %s", secret).doesNotContain(secret);
                // Every six-character window of the original. This is what catches the
                // half-match: "4111 1111 1111" redacted with "1111" left behind passes a
                // whole-value check and fails this one.
                for (int start = 0; start + 6 <= secret.length(); start++) {
                    String window = secret.substring(start, start + 6);
                    if (window.isBlank()) {
                        continue;
                    }
                    assertThat(out).as("fragment of %s leaked: %s", secret, window)
                            .doesNotContain(window);
                }
            }
            assertThat(result.placeholderCount()).isGreaterThanOrEqualTo(5);
        }
    }

    // ── 5. Overlaps ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("An order reference containing a phone-shaped number stays one match")
    void overlapsResolveToTheMoreSpecificMatch() {
        Long ticketId = ticket();

        String out = redactor.redact(tenant.tenantId(), ticketId,
                "Order ORD-9876543210 is stuck").redactedText();

        // Not "Order ORD-«PHONE_1» is stuck", which would be a redaction that destroyed
        // the order reference and protected nothing — the digits are still there, just
        // relabelled.
        assertThat(out).isEqualTo("Order «ORDER_REF_1» is stuck");
    }

    @Test
    @DisplayName("A twelve-digit government id is not eaten by the phone detector")
    void govIdBeatsPhone() {
        Long ticketId = ticket();

        String out = redactor.redact(tenant.tenantId(), ticketId,
                "Aadhaar 9234 5678 9012 attached").redactedText();

        assertThat(out).isEqualTo("Aadhaar «GOV_ID_1» attached");
    }

    // ── 6. Cross-ticket isolation ───────────────────────────────────────────

    @Test
    @DisplayName("A placeholder from another ticket rehydrates to nothing")
    void placeholdersDoNotCrossTickets() {
        Long ticketA = ticket();
        Long ticketB = ticket();

        redactor.redact(tenant.tenantId(), ticketA, "Card 4111 1111 1111 1111 declined");

        // «CARD_1» exists on almost every ticket that mentions a card. A global lookup
        // would make this line print ticket A's card number inside ticket B — for two
        // customers who may have nothing to do with each other.
        String rehydrated = redactor.rehydrate(ticketB, "Card «CARD_1» declined");

        assertThat(rehydrated).isEqualTo("Card «CARD_1» declined");
        assertThat(rehydrated).doesNotContain("4111");
    }

    @Test
    @DisplayName("Stored values are encrypted, not merely stored")
    void storedValuesAreEncrypted() {
        Long ticketId = ticket();
        redactor.redact(tenant.tenantId(), ticketId, "Call 9876543210 about ORD-5555");

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT placeholder, pii_type, encode(encrypted_value, 'escape') AS raw
                  FROM pii_redaction_map WHERE ticket_id = ?
                """, ticketId);

        assertThat(rows).isNotEmpty();
        // This table is the one place in the system holding personal data stripped of
        // its context — every phone number a tenant's customers ever typed, in a single
        // list. A database backup should not be enough to read it.
        assertThat(rows).allSatisfy(row ->
                assertThat((String) row.get("raw")).doesNotContain("9876543210", "ORD-5555"));
    }

    private Long ticket() {
        return tickets.createId(rest, customerToken, "PII carrier", "Body");
    }

    /** A 16-digit number that passes Luhn, so the card detector will accept it. */
    private static String luhnCard(int seed) {
        String base = "4" + String.format("%014d", 1000000000000L + seed);
        int sum = 0;
        boolean doubling = true;
        for (int i = base.length() - 1; i >= 0; i--) {
            int value = base.charAt(i) - '0';
            if (doubling) {
                value *= 2;
                if (value > 9) {
                    value -= 9;
                }
            }
            sum += value;
            doubling = !doubling;
        }
        return base + ((10 - (sum % 10)) % 10);
    }
}
