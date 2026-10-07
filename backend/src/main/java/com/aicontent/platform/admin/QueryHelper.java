package com.aicontent.platform.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small builder for dynamic WHERE clauses with named parameters (values are never concatenated into SQL). */
final class QueryHelper {

    private final List<String> conditions = new ArrayList<>();
    private final Map<String, Object> params = new HashMap<>();

    QueryHelper add(String condition, String name, Object value) {
        conditions.add(condition);
        params.put(name, value);
        return this;
    }

    String where() {
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    Map<String, Object> params() {
        return params;
    }
}
