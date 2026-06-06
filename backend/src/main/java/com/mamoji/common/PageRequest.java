package com.mamoji.common;

import java.util.Map;

public record PageRequest(int page, int size) {
    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    public static PageRequest from(Map<String, String> params) {
        return from(params, DEFAULT_SIZE);
    }

    public static PageRequest from(Map<String, String> params, int defaultSize) {
        return new PageRequest(
            parseInt(params.get("page"), DEFAULT_PAGE),
            parseInt(params.get("size"), defaultSize)
        );
    }

    public PageRequest {
        page = Math.max(page, 0);
        size = Math.min(Math.max(size, 1), MAX_SIZE);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
