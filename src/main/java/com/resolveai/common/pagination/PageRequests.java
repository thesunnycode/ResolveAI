package com.resolveai.common.pagination;

/** Shared clamping rules for paginated endpoints. */
public final class PageRequests {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;

    private PageRequests() {
    }

    /**
     * Clamps a requested page size into range.
     *
     * <p><b>Clamped, not rejected</b>, and that is a deliberate contract choice.
     * {@code size=1000} from a client trying to page less often is a reasonable thing to
     * attempt and an unreasonable thing to serve; answering with 100 rows and a cursor gives
     * them a working call, where a {@code 400} gives them an error to handle for no gain.
     * The cap itself is not negotiable — it bounds the work one request can ask for.
     */
    public static int clampSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_SIZE;
        }
        return Math.max(1, Math.min(MAX_SIZE, requested));
    }
}
