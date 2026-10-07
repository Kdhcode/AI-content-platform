package com.aicontent.platform.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RssNewsSourceAdapterTest {

    private HttpServer server;
    private RssNewsSourceAdapter adapter;

    private static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>가상일보</title>
            <item><title>[속보] 가상시 폭우</title><link>https://example.test/a/1</link>
            <pubDate>Thu, 01 Oct 2026 00:30:00 GMT</pubDate><description>&lt;p&gt;본문 &amp;amp; 내용&lt;/p&gt;</description></item>
            <item><title>두번째</title><link>https://example.test/a/2</link></item>
            </channel></rss>
            """;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> reply(ex, 200, RSS));
        server.createContext("/429", ex -> reply(ex, 429, ""));
        server.createContext("/500", ex -> reply(ex, 500, ""));
        server.createContext("/404", ex -> reply(ex, 404, ""));
        server.createContext("/bad", ex -> reply(ex, 200, "<rss><channel>"));
        server.createContext("/xxe", ex -> reply(ex, 200,
                "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss><channel><item><title>&x;</title></item></channel></rss>"));
        server.createContext("/slow", ex -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            reply(ex, 200, RSS);
        });
        server.start();
        adapter = new RssNewsSourceAdapter(HttpClient.newHttpClient(), Duration.ofMillis(500));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) {
        try {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
            if (b.length > 0) { ex.getResponseBody().write(b); }
            ex.close();
        } catch (java.io.IOException ignored) { }
    }

    private NewsSource src(String path) {
        return new NewsSource(1, "t", NewsSourceType.RSS, "http://127.0.0.1:" + server.getAddress().getPort() + path,
                true, "ACTIVE", 600, Map.of(), null, null, 0, null);
    }

    private SourceFetchException.Kind kindOf(String path) {
        return assertThrows(SourceFetchException.class, () -> adapter.fetchLatest(src(path), 10)).kind();
    }

    @Test
    void parsesItemsAndCleansHtml() throws Exception {
        List<CollectedArticle> list = adapter.fetchLatest(src("/ok"), 10);
        assertEquals(2, list.size());
        assertEquals("[속보] 가상시 폭우", list.get(0).title());
        assertEquals("https://example.test/a/1", list.get(0).url());
        assertEquals("가상일보", list.get(0).publisherName());
        assertEquals("본문 & 내용", list.get(0).analysisText());
    }

    @Test
    void limitIsApplied() throws Exception {
        assertEquals(1, adapter.fetchLatest(src("/ok"), 1).size());
    }

    @Test
    void errorKinds() {
        assertEquals(SourceFetchException.Kind.RATE_LIMITED, kindOf("/429"));
        assertEquals(SourceFetchException.Kind.SERVER_ERROR, kindOf("/500"));
        assertEquals(SourceFetchException.Kind.CLIENT_ERROR, kindOf("/404"));
        assertEquals(SourceFetchException.Kind.PARSE_ERROR, kindOf("/bad"));
        assertEquals(SourceFetchException.Kind.TIMEOUT, kindOf("/slow"));
    }

    @Test
    void doctypeIsRejected() {
        assertEquals(SourceFetchException.Kind.PARSE_ERROR, kindOf("/xxe"));
    }

    @Test
    void retryability() {
        assertTrue(SourceFetchException.Kind.TIMEOUT.retryable());
        assertTrue(!SourceFetchException.Kind.CLIENT_ERROR.retryable());
    }
}
