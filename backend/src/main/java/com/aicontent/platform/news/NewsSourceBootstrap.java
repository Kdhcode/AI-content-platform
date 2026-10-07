package com.aicontent.platform.news;

import com.aicontent.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Upserts {@code app.news.sources} into news_source at start-up. No source exists unless configured. */
@Component
public class NewsSourceBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NewsSourceBootstrap.class);

    private final NewsSourceRepository repository;
    private final AppProperties props;

    public NewsSourceBootstrap(NewsSourceRepository repository, AppProperties props) {
        this.repository = repository;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (var d : props.news().sources()) {
            if (d.name() == null || d.name().isBlank() || d.type() == null) {
                log.warn("ignoring news source definition without name/type");
                continue;
            }
            NewsSourceType type;
            try {
                type = NewsSourceType.valueOf(d.type().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("ignoring news source '{}': unknown type '{}'", d.name(), d.type());
                continue;
            }
            repository.upsertDefinition(d.name().trim(), type, d.baseUrl(), d.enabled(), d.intervalSeconds(), d.config());
        }
    }
}
