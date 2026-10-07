package com.aicontent.platform.common;

import java.util.List;

/** Pagination envelope: page is 0-based. */
public record PageResponse<T>(List<T> items, long totalElements, int totalPages, int page, int size) {

    public static <T> PageResponse<T> of(List<T> items, long totalElements, PageParams params) {
        int totalPages = params.size() == 0 ? 0 : (int) ((totalElements + params.size() - 1) / params.size());
        return new PageResponse<>(items, totalElements, totalPages, params.page(), params.size());
    }
}
