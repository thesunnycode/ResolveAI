package com.resolveai.common.pagination;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * An opaque marker for "resume after this row", encoding the sort key of the last row on the
 * previous page.
 *
 * <h2>Why {@code (createdAt, id)} and not just {@code createdAt}</h2>
 *
 * <p>Two tickets created in the same millisecond — which is not a rare event during a burst,
 * and is the normal case when a script files a batch — make a timestamp-only boundary
 * ambiguous. {@code WHERE created_at < :c} skips the second one; {@code <=} returns the
 * first one again. Adding the primary key makes the key total, so
 * {@code (created_at, id) < (:c, :i)} has exactly one correct answer.
 *
 * <h2>Why cursors at all, rather than {@code page=2}</h2>
 *
 * <p>Tickets leave the filtered set while an agent is paging through it — that is what
 * resolving a ticket does. With {@code OFFSET 25}, resolving one ticket on page 1 shifts
 * everything up by one and <b>page 2 silently skips a ticket the agent never saw.</b> That
 * is a correctness bug, not a performance one, and no amount of indexing fixes it. A keyset
 * cursor is stable under concurrent mutation because it names a row rather than a position.
 *
 * <h2>Opaque, but not secret</h2>
 *
 * <p>base64url of a tiny JSON object. Anyone can decode it, and nothing in it is sensitive —
 * a timestamp and an id the caller was just shown. The encoding exists so that clients
 * treat it as a token rather than parsing it, which is what leaves the sort key free to
 * change later.
 *
 * @param createdAt the last row's {@code created_at}
 * @param id        the last row's primary key, breaking ties
 */
public record Cursor(OffsetDateTime createdAt, Long id) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /**
     * Hand-built JSON rather than Jackson.
     *
     * <p>Two fields and no nesting; a mapper here would add a dependency to a value type for
     * the sake of eleven characters of output, and — more to the point — would make the wire
     * format depend on mapper configuration that is tuned for API responses.
     */
    public String encode() {
        String json = "{\"c\":\"" + createdAt + "\",\"i\":" + id + "}";
        return ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor a client sent back.
     *
     * <p><b>Every malformed input here is a {@code 400 INVALID_CURSOR}, never a 500.</b> This
     * value arrives from the network and will be fuzzed, truncated by a URL shortener, and
     * pasted with a trailing space. A stack trace in the logs for each of those is noise,
     * and a 500 tells a client to retry something that will never work.
     */
    public static Cursor decode(String encoded) {
        try {
            String json = new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
            int cStart = json.indexOf("\"c\":\"") + 5;
            int cEnd = json.indexOf('"', cStart);
            int iStart = json.indexOf("\"i\":") + 4;
            int iEnd = json.indexOf('}', iStart);
            if (cStart < 5 || cEnd < 0 || iStart < 4 || iEnd < 0) {
                throw new IllegalArgumentException("missing field");
            }
            return new Cursor(
                    OffsetDateTime.parse(json.substring(cStart, cEnd)),
                    Long.parseLong(json.substring(iStart, iEnd).trim()));
        } catch (IllegalArgumentException | DateTimeParseException | IndexOutOfBoundsException e) {
            throw new ApiException(ErrorCode.INVALID_CURSOR,
                    "The cursor is not valid. Start from the first page.", e);
        }
    }
}
