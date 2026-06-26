package com.resolveai.common.pagination;

import java.util.List;
import java.util.function.Function;

/**
 * The wrapper every cursor-paginated list endpoint returns.
 *
 * <p><b>A wrapper rather than a bare array, consistently across the API.</b> Doc 05 calls
 * this a deliberate REST deviation and it is: `GET /tickets` returning a JSON array would be
 * the more orthodox answer. But a top-level array has nowhere to put the next cursor, and
 * a response that is an array today and an object tomorrow is a breaking change for every
 * client.
 *
 * <p><b>There is no {@code totalElements}.</b> Counting a filtered set costs a second query
 * over the same predicate on every page, and nobody uses the number: an agent working a
 * queue wants the next page, not the size of the backlog. {@code hasNext} is what a "Load
 * more" button actually needs.
 *
 * @param data       the rows, at most {@code pagination.size} of them
 * @param pagination the cursor for the next page, and whether there is one
 */
public record CursorPage<T>(List<T> data, PageInfo pagination) {

    /**
     * @param size       the page size that was applied, after clamping
     * @param nextCursor null on the last page
     * @param hasNext    whether {@code nextCursor} will return anything
     */
    public record PageInfo(int size, String nextCursor, boolean hasNext) {
    }

    /**
     * Builds a page from {@code size + 1} rows fetched from the database.
     *
     * <p><b>Over-fetching by one is how {@code hasNext} is answered without a second
     * query.</b> The alternative — a {@code COUNT(*)} over the same predicate — doubles the
     * cost of every page to learn one boolean, and is still only an estimate by the time the
     * client reads it.
     *
     * @param fetched   up to {@code size + 1} rows in sort order
     * @param size      the requested page size
     * @param cursorFor extracts the cursor from the last row that is actually returned
     */
    public static <T> CursorPage<T> of(List<T> fetched, int size, Function<T, Cursor> cursorFor) {
        boolean hasNext = fetched.size() > size;
        List<T> data = hasNext ? fetched.subList(0, size) : fetched;
        String next = hasNext && !data.isEmpty()
                ? cursorFor.apply(data.get(data.size() - 1)).encode()
                : null;
        return new CursorPage<>(List.copyOf(data), new PageInfo(size, next, hasNext));
    }

    public static <T> CursorPage<T> empty(int size) {
        return new CursorPage<>(List.of(), new PageInfo(size, null, false));
    }

    /** Maps the rows, keeping the pagination as it is. */
    public <R> CursorPage<R> map(Function<T, R> mapper) {
        return new CursorPage<>(data.stream().map(mapper).toList(), pagination);
    }
}
