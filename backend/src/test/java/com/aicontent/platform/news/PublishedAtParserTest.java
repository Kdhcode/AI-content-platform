package com.aicontent.platform.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class PublishedAtParserTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    void parsesIso() {
        var r = PublishedAtParser.parse("2026-10-01T09:30:00+09:00", KST);
        assertTrue(r.isPresent());
        assertEquals(java.time.Instant.parse("2026-10-01T00:30:00Z"), r.get().toInstant());
    }

    @Test
    void parsesRfc1123() {
        var r = PublishedAtParser.parse("Thu, 01 Oct 2026 00:30:00 GMT", KST);
        assertTrue(r.isPresent());
        assertEquals(java.time.Instant.parse("2026-10-01T00:30:00Z"), r.get().toInstant());
    }

    @Test
    void unparseableOrBlankIsEmpty() {
        assertFalse(PublishedAtParser.parse("어제 오후 3시", KST).isPresent());
        assertFalse(PublishedAtParser.parse("  ", KST).isPresent());
        assertFalse(PublishedAtParser.parse(null, KST).isPresent());
    }
}
