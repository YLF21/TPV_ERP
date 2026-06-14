package com.tpverp.backend.verifactu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ImmutableJson {

    private ImmutableJson() {
    }

    static Map<String, Object> copy(Map<String, Object> source) {
        Objects.requireNonNull(source, "source");
        var copy = new LinkedHashMap<String, Object>(source.size());
        source.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "snapshot key"),
                copyValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<Object, Object>(map.size());
            map.forEach((key, nested) -> copy.put(key, copyValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(copyValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
