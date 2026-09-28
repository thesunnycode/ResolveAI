package com.resolveai.platform.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resolveai.ticketing.domain.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TriageSignalsParsingTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static String signals(String entities) {
        return """
                {"category":"PAYMENT","reportedImpact":"TEAM","serviceDownClaimed":false,
                 "dataLossClaimed":false,"paymentAffected":true,"linguisticUrgency":"MEDIUM",
                 "extractedEntities":%s,"confidence":0.9}
                """.formatted(entities);
    }

    @Test
    @DisplayName("a list of entities (two cards, several order ids) no longer fails the whole triage")
    void arraysAreJoined() {
        TriageSignals s = mapper.readValue(
                signals("{\"paymentMethod\":[\"Visa\",\"Mastercard\"],\"orderRef\":[\"«ORDER_REF_1»\",\"«ORDER_REF_2»\"]}"),
                TriageSignals.class);

        assertThat(s.category()).isEqualTo(Category.PAYMENT);
        assertThat(s.extractedEntities())
                .containsEntry("paymentMethod", "Visa, Mastercard")
                .containsEntry("orderRef", "«ORDER_REF_1», «ORDER_REF_2»");
    }

    @Test
    void scalarsNullsAndNestedObjectsAreTolerated() {
        TriageSignals s = mapper.readValue(
                signals("{\"errorCode\":502,\"browser\":null,\"card\":{\"brand\":\"Visa\"},\"plain\":\"x\"}"),
                TriageSignals.class);

        assertThat(s.extractedEntities())
                .containsEntry("errorCode", "502")
                .containsEntry("plain", "x")
                .containsEntry("card", "{\"brand\":\"Visa\"}")
                .doesNotContainKey("browser");
    }

    @Test
    void missingOrNonObjectEntitiesBecomeEmpty() {
        assertThat(mapper.readValue(signals("null"), TriageSignals.class).extractedEntities()).isEmpty();
        assertThat(mapper.readValue(signals("[\"a\"]"), TriageSignals.class).extractedEntities()).isEmpty();
    }

    @Test
    @DisplayName("leniency is limited to the advisory field: a missing decision field still rejects")
    void decisionFieldsStayStrict() {
        String noCategory = signals("{}").replace("\"category\":\"PAYMENT\",", "");
        assertThatThrownBy(() -> mapper.readValue(noCategory, TriageSignals.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
