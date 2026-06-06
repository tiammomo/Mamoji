package com.mamoji.common;

import java.util.List;

public class PagedResponse<T> {
    public final List<T> content;
    public final long totalElements;
    public final int totalPages;
    public final int size;
    public final int number;

    public PagedResponse(List<T> content, long totalElements, int totalPages, int size, int number) {
        this.content = content;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.size = size;
        this.number = number;
    }

    public static <T> PagedResponse<T> of(List<T> items, int page, int size) {
        return of(items, new PageRequest(page, size));
    }

    public static <T> PagedResponse<T> of(List<T> items, PageRequest pageRequest) {
        int safePage = pageRequest.page();
        int safeSize = pageRequest.size();
        int from = Math.min(safePage * safeSize, items.size());
        int to = Math.min(from + safeSize, items.size());
        int totalPages = (int) Math.ceil((double) items.size() / safeSize);
        return new PagedResponse<>(items.subList(from, to), items.size(), totalPages, safeSize, safePage);
    }
}
