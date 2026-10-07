package com.aicontent.platform.news;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UrlNormalizerTest {

    @Test
    void normalizesSchemeHostPortFragmentTrackingAndQueryOrder() {
        assertEquals("https://example.com/news/a?a=1&b=2",
                UrlNormalizer.normalize("HTTP://www.Example.com:80/news/a/?utm_source=x&b=2&a=1#frag"));
    }

    @Test
    void sameArticleWithDifferentDecorationsMapsToOneKey() {
        String a = UrlNormalizer.normalize("https://example.com/n/1?utm_campaign=z");
        String b = UrlNormalizer.normalize("http://www.example.com/n/1/");
        assertEquals(a, b);
    }
}
