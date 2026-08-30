package com.resolveai.knowledge.web.dto;

/**
 * One item from a bulk import that didn't become a document.
 *
 * @param source where this came from, for display — {@code "articles.csv row 3"} or
 *               {@code "password-reset-runbook.pdf"}, not a {@link com.resolveai.knowledge.domain.DocumentSource}
 * @param title  the title the item would have had, so the admin can find it in the original file
 * @param reason why it was skipped, in plain language
 */
public record SkippedItemResponse(String source, String title, String reason) {
}
