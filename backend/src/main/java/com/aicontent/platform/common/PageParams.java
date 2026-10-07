package com.aicontent.platform.common;

import java.util.Map;

/**
 * Validated paging and sorting input shared by every list API.
 * Sorting is whitelist based (sort key -> SQL column) so user input never reaches an ORDER BY clause.
 */
public record PageParams(int page, int size, String orderBy) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public int offset() {
        return page * size;
    }

    /**
     * @param sort           raw value like {@code "publishedAt,desc"} (nullable)
     * @param allowed        sort key -> SQL expression
     * @param defaultOrderBy complete ORDER BY body used when {@code sort} is blank
     */
    public static PageParams of(Integer page, Integer size, String sort, Map<String, String> allowed, String defaultOrderBy) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "page는 0 이상이어야 합니다.");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "size는 1~" + MAX_SIZE + " 사이여야 합니다.");
        }
        return new PageParams(p, s, resolveOrderBy(sort, allowed, defaultOrderBy));
    }

    static String resolveOrderBy(String sort, Map<String, String> allowed, String defaultOrderBy) {
        if (sort == null || sort.isBlank()) {
            return defaultOrderBy;
        }
        String[] parts = sort.split(",", -1);
        String column = allowed.get(parts[0].trim());
        if (column == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "지원하지 않는 정렬 키입니다: " + parts[0].trim());
        }
        String direction = "DESC";
        if (parts.length > 1) {
            String d = parts[1].trim().toLowerCase();
            if (d.equals("asc")) {
                direction = "ASC";
            } else if (!d.equals("desc")) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "정렬 방향은 asc 또는 desc여야 합니다.");
            }
        }
        // id is always appended so that pagination is stable when the primary key has ties.
        return column + " " + direction + " NULLS LAST, " + allowed.getOrDefault("id", "id") + " DESC";
    }
}
