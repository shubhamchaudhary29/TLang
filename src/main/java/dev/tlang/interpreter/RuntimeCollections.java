package dev.tlang.interpreter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates the mutable collection values exposed to TLang programs.
 *
 * <p>The synchronized wrappers make each primitive list/map operation atomic
 * and prevent structural corruption when a value is intentionally shared by
 * concurrent HTTP handlers. Callers that iterate must use the snapshot helpers
 * or synchronize on the collection for the duration of the iteration.</p>
 */
public final class RuntimeCollections {
    private RuntimeCollections() {}

    public static <T> List<T> newList() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    public static <T> List<T> newList(List<? extends T> values) {
        return Collections.synchronizedList(new ArrayList<>(values));
    }

    public static <K, V> Map<K, V> newMap() {
        return Collections.synchronizedMap(new LinkedHashMap<>());
    }

    public static <K, V> Map<K, V> newMap(Map<? extends K, ? extends V> values) {
        return Collections.synchronizedMap(new LinkedHashMap<>(values));
    }

    public static <T> List<T> snapshot(List<? extends T> values) {
        synchronized (values) {
            return new ArrayList<>(values);
        }
    }

    public static <K, V> Map<K, V> snapshot(Map<? extends K, ? extends V> values) {
        synchronized (values) {
            return new LinkedHashMap<>(values);
        }
    }
}
