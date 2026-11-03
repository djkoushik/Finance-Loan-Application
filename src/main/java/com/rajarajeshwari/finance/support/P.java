package com.rajarajeshwari.finance.support;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parameter-map builder plus typed readers for JSON request bodies. */
public final class P {

    private P() {
    }

    /** P.of("a", 1, "b", 2) */
    public static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) {
            return "";
        }
        return String.valueOf(v).trim();
    }

    public static String required(Map<String, Object> body, String key, String label) {
        String s = str(body, key);
        if (s.isEmpty()) {
            throw new ApiException(400, label + " is required");
        }
        return s;
    }

    public static int integer(Map<String, Object> body, String key, String label) {
        String s = str(body, key);
        if (s.isEmpty()) {
            throw new ApiException(400, label + " is required");
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new ApiException(400, label + " must be a whole number");
        }
    }

    public static int integerOr(Map<String, Object> body, String key, int dflt) {
        String s = str(body, key);
        if (s.isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new ApiException(400, key + " must be a whole number");
        }
    }

    public static LocalDate date(Map<String, Object> body, String key, String label) {
        return parseDate(required(body, key, label), label);
    }

    public static LocalDate parseDate(String s, String label) {
        if (s == null || s.trim().isEmpty()) {
            throw new ApiException(400, label + " is required");
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(400, label + " is not a valid date (use yyyy-mm-dd)");
        }
    }

    /** Empty string becomes null so optional query params can be bound as null. */
    public static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
