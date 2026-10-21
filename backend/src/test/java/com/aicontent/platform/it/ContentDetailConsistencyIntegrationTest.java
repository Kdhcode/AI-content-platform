package com.aicontent.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.content.ContentRelationService;
import com.aicontent.platform.content.ContentRepository;
import com.aicontent.platform.content.ContentService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The content detail (title, body, status, version, sources, links) must come from one database snapshot. A writer
 * keeps adding sources and links (each change is one transaction and raises the version by one) while readers keep
 * reading the detail; every read must satisfy version == (sources - 1) + links. A detail assembled from several
 * statements can see a source list from before a commit and a version / link list from after it.
 */
class ContentDetailConsistencyIntegrationTest extends IntegrationTestBase {

    @Autowired ContentService contents;
    @Autowired ContentRelationService relations;
    @Autowired ContentRepository query;

    @Test
    void readsNeverMixTwoCommitsWhileSourcesAndLinksChange() throws Exception {
        long issue = jdbc.sql("INSERT INTO issue (title, status) VALUES ('스냅샷 이슈', 'ACTIVE') RETURNING id").query(Long.class).single();
        long id = contents.create(issue, "NEWS", "스냅샷", null, "snapshot-" + System.nanoTime(), "op", null).content().id();

        AtomicBoolean done = new AtomicBoolean(false);
        AtomicInteger reads = new AtomicInteger();
        ConcurrentLinkedQueue<String> broken = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> readers = new ArrayList<>();
            for (int r = 0; r < 3; r++) {
                readers.add(pool.submit(() -> {
                    while (!done.get()) {
                        var d = query.detail(id).orElseThrow();
                        reads.incrementAndGet();
                        long expected = (d.sources().size() - 1) + d.links().size();
                        if (d.version() != expected) {
                            broken.add("version " + d.version() + " with " + d.sources().size() + " sources and " + d.links().size() + " links");
                        }
                    }
                    return null;
                }));
            }
            Future<?> writer = pool.submit(() -> {
                long v = 0;
                for (int i = 0; i < 150; i++) {
                    if (i % 2 == 0) {
                        relations.addSource(id, v, new ContentRelationService.SourceInput("MANUAL", null, null, null, "자료 " + i, null), "op", null);
                    } else {
                        relations.addLink(id, v, new ContentRelationService.LinkInput("REFERENCE", "", "https://ref.example.test/" + i, null, null, null), "op", null);
                    }
                    v++;
                }
                return null;
            });
            writer.get();
            done.set(true);
            for (Future<?> f : readers) f.get();
        } finally {
            done.set(true);
            pool.shutdownNow();
        }
        assertThat(reads.get()).isGreaterThan(150);
        assertThat(broken).as("inconsistent reads out of " + reads.get()).isEmpty();
        var last = query.detail(id).orElseThrow();
        assertThat(last.version()).isEqualTo(150);
        assertThat(last.sources()).hasSize(76);
        assertThat(last.links()).hasSize(75);
    }
}
