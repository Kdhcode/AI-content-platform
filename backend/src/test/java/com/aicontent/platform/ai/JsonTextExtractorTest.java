package com.aicontent.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class JsonTextExtractorTest {

    @Test
    void plainObject() {
        assertEquals("{\"a\":1}", JsonTextExtractor.extractObject("{\"a\":1}"));
    }

    @Test
    void markdownFenceAndProse() {
        assertEquals("{\"a\":{\"b\":2}}", JsonTextExtractor.extractObject("결과:\n```json\n{\"a\":{\"b\":2}}\n```\n끝"));
    }

    @Test
    void bracesInsideStringsAreIgnored() {
        String obj = "{\"reason\":\"a } b { c \\\" }\",\"x\":1}";
        assertEquals(obj, JsonTextExtractor.extractObject("pre " + obj + " post"));
    }

    @Test
    void noObjectOrUnbalancedGivesNull() {
        assertNull(JsonTextExtractor.extractObject("no json here"));
        assertNull(JsonTextExtractor.extractObject("{\"a\":1"));
        assertNull(JsonTextExtractor.extractObject(null));
    }

    @Test
    void skipsUnbalancedFirstBraceAndFindsLaterObject() {
        assertEquals("{\"ok\":true}", JsonTextExtractor.extractObject("{ broken {\"ok\":true}"));
    }
}
