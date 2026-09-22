package com.tpverp.backend.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Natural ordering of product codes, shared by paged stock and its exports. */
public final class ProductCodeOrder {
    private static final Pattern PARTS = Pattern.compile("[0-9]+|[^0-9]+");

    private ProductCodeOrder() { }

    /** The expression is an internal SQL column name, never request input. */
    public static String sqlKey(String expression) {
        String digits = "coalesce(nullif(ltrim(" + expression + ", '0'), ''), '0')";
        String partDigits = "coalesce(nullif(ltrim(parts.value[1], '0'), ''), '0')";
        // Most codes are numeric. Avoid regexp_matches for this common case.
        // Encoding the digit count avoids numeric casts and preserves arbitrarily long codes.
        return "(case when " + expression + " ~ '^[0-9]+$' then array['0' || lpad(length("
                + digits + ")::text, 10, '0') || " + digits + "] else ("
                + "select array_agg(case when parts.value[1] ~ '^[0-9]+$' "
                + "then '0' || lpad(length(" + partDigits + ")::text, 10, '0') || " + partDigits
                + " else '1' || parts.value[1] end order by parts.ordinality) "
                + "from regexp_matches(lower(" + expression + "), '[0-9]+|[^0-9]+', 'g') "
                + "with ordinality as parts(value, ordinality)) end) collate \"C\"";
    }

    public static Comparator<String> comparator() {
        return (left, right) -> {
            List<String> leftParts = key(left);
            List<String> rightParts = key(right);
            if (leftParts.isEmpty() || rightParts.isEmpty()) {
                return leftParts.isEmpty() ? (rightParts.isEmpty() ? 0 : 1) : -1;
            }
            for (int index = 0; index < Math.min(leftParts.size(), rightParts.size()); index++) {
                int comparison = leftParts.get(index).compareTo(rightParts.get(index));
                if (comparison != 0) return comparison;
            }
            return Integer.compare(leftParts.size(), rightParts.size());
        };
    }

    private static List<String> key(String value) {
        if (value == null || value.isEmpty()) return List.of();
        var matcher = PARTS.matcher(value.toLowerCase(Locale.ROOT));
        var result = new ArrayList<String>();
        while (matcher.find()) {
            String part = matcher.group();
            if (part.charAt(0) >= '0' && part.charAt(0) <= '9') {
                int start = 0;
                while (start < part.length() - 1 && part.charAt(start) == '0') start++;
                String digits = part.substring(start);
                String length = Integer.toString(digits.length());
                result.add("0" + "0".repeat(10 - length.length()) + length + digits);
            } else {
                result.add("1" + part);
            }
        }
        return result;
    }
}
