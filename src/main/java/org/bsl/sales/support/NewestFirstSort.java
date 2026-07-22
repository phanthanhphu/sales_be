package org.bsl.sales.support;

import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.function.Function;

/**
 * Shared ordering used by management tables: records created most recently are
 * displayed first. Updated time and Mongo id are deterministic tie breakers,
 * so editing an old record does not move it above a newly created record.
 */
public final class NewestFirstSort {
    private static final Comparator<LocalDateTime> DATE_DESC =
            Comparator.nullsLast(Comparator.reverseOrder());
    private static final Comparator<String> ID_DESC =
            Comparator.nullsLast(Comparator.reverseOrder());

    private NewestFirstSort() {
    }

    public static Sort mongo() {
        return Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("updatedAt"),
                Sort.Order.desc("_id")
        );
    }

    public static <T> Comparator<T> comparator(
            Function<T, LocalDateTime> createdAt,
            Function<T, LocalDateTime> updatedAt,
            Function<T, String> id
    ) {
        return Comparator
                .comparing(createdAt, DATE_DESC)
                .thenComparing(updatedAt, DATE_DESC)
                .thenComparing(id, ID_DESC);
    }
}
