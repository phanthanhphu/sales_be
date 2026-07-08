package org.bsl.sales.support;

import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates visible master-data keys such as VC000001, MI000001 and L000001.
 * Existing keys are scanned first so imported/manual records always continue
 * from the current highest number.
 */
public final class MasterDataSequentialKey {

    private MasterDataSequentialKey() {
    }

    public static AtomicLong counter(Collection<String> existingKeys, String prefix) {
        return new AtomicLong(maxNumber(existingKeys, prefix));
    }

    public static String next(AtomicLong counter, String prefix) {
        return format(prefix, counter.incrementAndGet());
    }

    public static void ensure(Supplier<String> getter, Consumer<String> setter, AtomicLong counter, String prefix) {
        String current = getter == null ? null : getter.get();
        if (current == null || current.trim().isEmpty()) {
            setter.accept(next(counter, prefix));
        }
    }

    public static long maxNumber(Collection<String> existingKeys, String prefix) {
        if (existingKeys == null || existingKeys.isEmpty()) {
            return 0L;
        }

        Pattern pattern = Pattern.compile('^' + Pattern.quote(prefix).toUpperCase(Locale.ROOT) + "(\\d+)$");
        long max = 0L;

        for (String raw : existingKeys) {
            if (raw == null) {
                continue;
            }
            Matcher matcher = pattern.matcher(raw.trim().toUpperCase(Locale.ROOT));
            if (!matcher.matches()) {
                continue;
            }
            try {
                max = Math.max(max, Long.parseLong(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // Ignore malformed legacy values.
            }
        }

        return max;
    }

    private static String format(String prefix, long number) {
        return String.format(Locale.ROOT, "%s%06d", prefix, number);
    }
}
