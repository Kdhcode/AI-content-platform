package com.aicontent.platform.news;

/**
 * What an adapter hands to the domain: raw, source-agnostic data. Parsing of the publication time and all
 * validation happen in the collection service, never in an adapter.
 */
public record CollectedArticle(
        String title,
        String url,
        String publisherName,
        String author,
        String category,
        String publishedAtRaw,
        String analysisText) {}
