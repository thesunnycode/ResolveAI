package com.resolveai.platform.ai.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads {@link TriageSignals#extractedEntities()} without letting its shape veto a triage.
 *
 * <p>The model is asked for {@code {"orderRef": "..."}} but, for a ticket that mentions two
 * cards or three order numbers, it reasonably answers {@code {"paymentMethod": ["Visa",
 * "Mastercard"]}}. With a strict {@code Map<String, String>} that one advisory field threw
 * away an otherwise valid classification - category, impact, urgency all correct - and
 * left the ticket untriaged and invisible to its team. The field is advisory (nothing
 * downstream decides on it; {@code EntityExtractor} does the real extraction), so the
 * strictness belongs on the decision fields, not here. Arrays are joined, scalars
 * stringified, nested objects kept as their JSON text, nulls dropped.
 */
public class LenientEntityMapDeserializer extends ValueDeserializer<Map<String, String>> {

    @Override
    public Map<String, String> deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonNode node = ctxt.readTree(p);
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String value = flatten(e.getValue());
            if (value != null && !value.isBlank()) {
                out.put(e.getKey(), value);
            }
        }
        return out;
    }

    private static String flatten(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isArray()) {
            return StreamSupport.stream(v.spliterator(), false)
                    .map(LenientEntityMapDeserializer::flatten)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.joining(", "));
        }
        if (v.isValueNode()) {
            return v.asString();
        }
        return v.toString();
    }
}
