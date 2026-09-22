package com.resolveai.drafting.domain;

/** Matches {@code ck_draft_status}. */
public enum DraftStatus {
    PENDING,
    SHOWN,
    SUPPRESSED_LOW_COVERAGE,
    SUPPRESSED_NO_EVIDENCE,
    FAILED;

    public boolean isSuppressed() {
        return this == SUPPRESSED_LOW_COVERAGE || this == SUPPRESSED_NO_EVIDENCE;
    }

    public boolean isTerminal() {
        return this != PENDING;
    }
}
