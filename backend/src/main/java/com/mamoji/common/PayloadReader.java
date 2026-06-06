package com.mamoji.common;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public final class PayloadReader {
    private PayloadReader() {
    }

    public static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static String textOr(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    public static String nullableText(Object value) {
        if (value == null) {
            return null;
        }
        String text = text(value);
        return text.isBlank() ? null : text;
    }

    public static BigDecimal number(Object value, BigDecimal fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return new BigDecimal(String.valueOf(value));
    }

    public static Optional<Long> optionalLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(String.valueOf(value)));
    }

    public static Optional<Integer> optionalInt(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(String.valueOf(value)));
    }

    public static long longValue(Object value, long fallback) {
        return optionalLong(value).orElse(fallback);
    }

    public static int intValue(Object value, int fallback) {
        return optionalInt(value).orElse(fallback);
    }

    public static boolean bool(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static int intParam(Map<String, String> params, String key, int fallback) {
        try {
            return params.get(key) == null ? fallback : Integer.parseInt(params.get(key));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static long longParam(Map<String, String> params, String key, long fallback) {
        try {
            return params.get(key) == null ? fallback : Long.parseLong(params.get(key));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static BigDecimal decimalParam(Map<String, String> params, String key, BigDecimal fallback) {
        try {
            return params.get(key) == null ? fallback : new BigDecimal(params.get(key));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
