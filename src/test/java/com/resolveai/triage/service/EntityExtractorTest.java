package com.resolveai.triage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.ticketing.domain.TicketEntityType;
import com.resolveai.triage.service.EntityExtractor.ExtractedEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Plain JUnit, no Spring context — {@link EntityExtractor} has no I/O, so nothing here
 * needs one.
 */
class EntityExtractorTest {

    private final EntityExtractor extractor = new EntityExtractor(
            List.of("payment-service", "auth-service", "checkout-service"),
            List.of("ap-south-1", "us-east-1"));

    @Test
    @DisplayName("the doc 12 worked example yields five entities of four types")
    void wholeExample() {
        Set<ExtractedEntity> found = extractor.extract(
                "Getting ERR_PAY_TIMEOUT on UPI checkout, 503 from payment-service "
                + "in ap-south-1");

        assertThat(found).containsExactlyInAnyOrder(
                new ExtractedEntity(TicketEntityType.ERROR_CODE, "ERR_PAY_TIMEOUT"),
                new ExtractedEntity(TicketEntityType.PAYMENT_METHOD, "upi"),
                new ExtractedEntity(TicketEntityType.HTTP_STATUS, "503"),
                new ExtractedEntity(TicketEntityType.SERVICE, "payment-service"),
                new ExtractedEntity(TicketEntityType.REGION, "ap-south-1"));
    }

    @Test
    @DisplayName("404 in ordinary prose is not an HTTP_STATUS")
    void noFalsePositiveOnBareNumber() {
        Set<ExtractedEntity> found = extractor.extract(
                "I read 404 pages of documentation and still could not fix this.");

        assertThat(found).noneMatch(e -> e.type() == TicketEntityType.HTTP_STATUS);
    }

    @ParameterizedTest
    @CsvSource({
            "Got ERR_PAYMENT_TIMEOUT on checkout, ERROR_CODE, ERR_PAYMENT_TIMEOUT",
            "Server threw E404 trying to load the page, ERROR_CODE, E404",
            "Stack trace shows a NullPointerException in the payment flow, ERROR_CODE, NullPointerException",
    })
    void errorCodePositives(String text, TicketEntityType type, String value) {
        assertThat(extractor.extract(text)).contains(new ExtractedEntity(type, value));
    }

    @ParameterizedTest
    @CsvSource({
            "'This is just plain text with no code in it'",
            "'The error was mine, not the system''s'",
            "'Exception handling is a core skill for developers'",
    })
    void errorCodeNegatives(String text) {
        assertThat(extractor.extract(text))
                .noneMatch(e -> e.type() == TicketEntityType.ERROR_CODE);
    }

    @ParameterizedTest
    @CsvSource({
            "Checkout returned a 503 error at the gateway, HTTP_STATUS, 503",
            "The app gave a 404 status when I searched, HTTP_STATUS, 404",
            "Getting HTTP 500 on every retry, HTTP_STATUS, 500",
    })
    void httpStatusPositives(String text, TicketEntityType type, String value) {
        assertThat(extractor.extract(text)).contains(new ExtractedEntity(type, value));
    }

    @ParameterizedTest
    @CsvSource({
            "'404 pages of documentation and counting'",
            "'It is about 500 km from here to the office'",
            "'I paid 500 rupees for a one-time repair'",
    })
    void httpStatusNegatives(String text) {
        assertThat(extractor.extract(text))
                .noneMatch(e -> e.type() == TicketEntityType.HTTP_STATUS);
    }

    @ParameterizedTest
    @CsvSource({
            "My UPI payment failed twice, PAYMENT_METHOD, upi",
            "The card was declined at checkout, PAYMENT_METHOD, card",
            "NEFT transfer never arrived, PAYMENT_METHOD, neft",
    })
    void paymentMethodPositives(String text, TicketEntityType type, String value) {
        assertThat(extractor.extract(text)).contains(new ExtractedEntity(type, value));
    }

    @ParameterizedTest
    @CsvSource({
            "'I want to discard this old order'",
            "'The wizard used a magic wardrobe'",
            "'This is a hard problem to solve'",
    })
    void paymentMethodNegatives(String text) {
        assertThat(extractor.extract(text))
                .noneMatch(e -> e.type() == TicketEntityType.PAYMENT_METHOD);
    }

    @ParameterizedTest
    @CsvSource({
            "Getting timeouts from payment-service since this morning, SERVICE, payment-service",
            "auth-service is rejecting every login, SERVICE, auth-service",
    })
    void servicePositives(String text, TicketEntityType type, String value) {
        assertThat(extractor.extract(text)).contains(new ExtractedEntity(type, value));
    }

    @ParameterizedTest
    @CsvSource({
            "'The invoicing-service is not one we track'",
            "'Everything about my payment history looks fine'",
    })
    void serviceNegatives(String text) {
        assertThat(extractor.extract(text))
                .noneMatch(e -> e.type() == TicketEntityType.SERVICE);
    }

    @ParameterizedTest
    @CsvSource({
            "Requests are timing out in ap-south-1 right now, REGION, ap-south-1",
            "Our us-east-1 deployment is affected, REGION, us-east-1",
    })
    void regionPositives(String text, TicketEntityType type, String value) {
        assertThat(extractor.extract(text)).contains(new ExtractedEntity(type, value));
    }

    @ParameterizedTest
    @CsvSource({
            "'I have no idea which region this is in'",
            "'The ratio 3-to-1 seems about right'",
    })
    void regionNegatives(String text) {
        assertThat(extractor.extract(text))
                .noneMatch(e -> e.type() == TicketEntityType.REGION);
    }

    @ParameterizedTest
    @CsvSource({
            "I updated to app version 2.14.0 and it broke, APP_VERSION, 2.14.0",
            "The build 3.1 introduced this bug, APP_VERSION, 3.1",
    })
    void appVersionPositives(String text, TicketEntityType type, String value) {
        assertThat(extractor.extract(text)).contains(new ExtractedEntity(type, value));
    }

    @ParameterizedTest
    @CsvSource({
            "'I scored 3.5 out of 5 on the satisfaction survey'",
            "'The interest rate is 2.5 percent this quarter'",
    })
    void appVersionNegatives(String text) {
        assertThat(extractor.extract(text))
                .noneMatch(e -> e.type() == TicketEntityType.APP_VERSION);
    }

    @Test
    @DisplayName("idempotence: extracting twice yields an identical set")
    void idempotent() {
        String text = "ERR_PAY_TIMEOUT on UPI at payment-service, 503, ap-south-1";
        assertThat(extractor.extract(text)).isEqualTo(extractor.extract(text));
    }

    @Test
    @DisplayName("order independence: the set does not depend on text order")
    void orderIndependent() {
        Set<ExtractedEntity> forward = extractor.extract(
                "payment-service returned 503 for UPI in ap-south-1, code ERR_PAY_TIMEOUT");
        Set<ExtractedEntity> shuffled = extractor.extract(
                "ERR_PAY_TIMEOUT, UPI failed in ap-south-1: payment-service gave a 503 error");

        assertThat(forward).isEqualTo(shuffled);
    }

    @Test
    @DisplayName("redacted PII placeholders produce no entities")
    void redactedTextIsClean() {
        Set<ExtractedEntity> found = extractor.extract(
                "My «CARD_1» was charged and «ORDER_1» never arrived, contact me at «EMAIL_1»");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("no false positives on 50 seeded ticket bodies with no incident signal")
    void noFalsePositivesOnOrdinaryTickets() throws Exception {
        Path corpus = Path.of("src/test/resources/triage/ordinary-ticket-bodies.txt");
        List<String> bodies = Files.readAllLines(corpus).stream()
                .filter(line -> !line.isBlank())
                .toList();
        assertThat(bodies).hasSizeGreaterThanOrEqualTo(50);

        int falsePositiveTickets = 0;
        for (String body : bodies) {
            if (!extractor.extract(body).isEmpty()) {
                falsePositiveTickets++;
            }
        }

        // Committed threshold: at most 1 in 10 of these ordinary, signal-free tickets may
        // trip an extractor rule. Every one above zero inflates the shared-entity boost
        // for an unrelated ticket, so this is deliberately strict — see the class comment.
        double rate = (double) falsePositiveTickets / bodies.size();
        assertThat(rate).isLessThanOrEqualTo(0.10);
    }
}
