package com.aicontent.platform.news;

import com.aicontent.platform.common.Json;
import com.aicontent.platform.config.AppProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the available source adapters. PLUG-IN POINT for a real provider (OPEN ITEM): add a
 * {@link NewsSourceAdapter} bean here (or annotate it as a component); {@link NewsSourceAdapters} picks it up by type.
 */
@Configuration
public class NewsAdapterConfig {

    @Bean
    NewsSourceAdapter rssNewsSourceAdapter(AppProperties props) {
        Duration timeout = Duration.ofSeconds(props.news().httpTimeoutSeconds());
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
        return new RssNewsSourceAdapter(client, timeout);
    }

    @Bean
    NewsSourceAdapter fixtureNewsSourceAdapter(Json json) {
        return new FixtureNewsSourceAdapter(json);
    }
}
