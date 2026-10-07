package os.aiworkforce.analytics.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.io.JsonStringEncoder;

/**
 * One text for one piece of JSON, whatever order or number format it arrived in.
 *
 * <p>A hash is only reproducible if the text it covers is. The detail of an audit entry goes into
 * the database as {@code jsonb}, which keeps no key order and rewrites numbers, and comes back out
 * as a map the writer never saw. So the hash must cover a form both ends can reach: keys sorted at
 * every depth, no whitespace, strings escaped one way, and every number written as the plain
 * decimal it stands for ({@code 1.0}, {@code 1} and {@code 1E0} are all {@code 1}).
 *
 * <p>Written by hand rather than through an {@code ObjectMapper}, because the result is part of
 * what every stored hash means: a mapper setting or a library upgrade that changed how it prints
 * would make every old entry look altered.
 */
final class CanonicalJson {

    private CanonicalJson() {}

    /** The canonical text for a JSON value: a map, a list, a string, a number, a boolean or null. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            appendObject(out, map);
        } else if (value instanceof Collection<?> list) {
            out.append('[');
            boolean first = true;
            for (Object element : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                append(out, element);
            }
            out.append(']');
        } else if (value instanceof Object[] array) {
            append(out, java.util.Arrays.asList(array));
        } else if (value instanceof Boolean bool) {
            out.append(bool ? "true" : "false");
        } else if (value instanceof Number number) {
            out.append(number(number));
        } else {
            quote(out, value.toString());
        }
    }

    private static void appendObject(StringBuilder out, Map<?, ?> map) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sorted.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            quote(out, entry.getKey());
            out.append(':');
            append(out, entry.getValue());
        }
        out.append('}');
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        JsonStringEncoder.getInstance().quoteAsString(text, out);
        out.append('"');
    }

    /**
     * A number as the plain decimal it stands for. Not a number JSON can carry (NaN, infinity) is
     * written as a string, so the hash is still defined; callers parse their input as JSON, which
     * cannot produce one.
     */
    private static String number(Number number) {
        if (number instanceof Double d && (d.isNaN() || d.isInfinite())
                || number instanceof Float f && (f.isNaN() || f.isInfinite())) {
            return '"' + number.toString() + '"';
        }
        BigDecimal decimal = number instanceof BigDecimal big ? big : new BigDecimal(number.toString());
        if (decimal.signum() == 0) {
            return "0";
        }
        return decimal.stripTrailingZeros().toPlainString();
    }
}
