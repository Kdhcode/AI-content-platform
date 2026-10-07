package com.aicontent.platform.news;

import java.util.List;

/**
 * Boundary between "how a particular site delivers news" and the domain. Site specific code (HTML structure,
 * API payloads, auth) must stay behind this interface; the collection service only sees {@link CollectedArticle}.
 *
 * <p>Where to plug in a real provider: implement this interface, register the bean in
 * {@code NewsAdapterConfig}, and create a {@code news_source} row (or an {@code app.news.sources} entry) whose
 * type matches {@link #type()}. Selecting the real provider is an OPEN ITEM.
 */
public interface NewsSourceAdapter {

    NewsSourceType type();

    /**
     * @param limit upper bound of articles to return, newest first when the source allows ordering
     * @throws SourceFetchException with a {@link SourceFetchException.Kind} describing timeout, 429, 5xx, parse error...
     */
    List<CollectedArticle> fetchLatest(NewsSource source, int limit) throws SourceFetchException;
}
