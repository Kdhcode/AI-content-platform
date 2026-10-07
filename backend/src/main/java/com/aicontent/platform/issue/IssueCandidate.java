package com.aicontent.platform.issue;

import java.time.OffsetDateTime;
import java.util.List;

/** An existing issue retrieved for an article, with its embedding similarity (cosine, 1 = identical). */
public record IssueCandidate(long id, String title, String summary, List<String> keyFacts, OffsetDateTime firstPublishedAt,
                             OffsetDateTime lastUpdatedAt, int articleCount, int publisherCount, double similarity) {}
