package com.aicontent.platform.news;

public enum NewsSourceType {
    RSS,
    API,
    WEB,
    /** Reads articles from a local JSON file; for development, demos and tests. Never a real news provider. */
    FIXTURE
}
