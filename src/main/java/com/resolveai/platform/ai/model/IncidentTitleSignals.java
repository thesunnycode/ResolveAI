package com.resolveai.platform.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The model's entire contribution to an incident: a human-readable title and a two-sentence
 * summary. Nothing here decides that an incident exists — {@code CorrelationGate} already
 * did, deterministically, before this is ever called. See
 * {@code IncidentTitleGenerator}'s class comment.
 *
 * @param title   under 80 characters
 * @param summary two sentences, plain language, no markdown
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IncidentTitleSignals(String title, String summary) {
}
