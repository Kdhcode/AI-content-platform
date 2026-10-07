package com.aicontent.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingTextBuilderTest {

    @Test
    void joinsPartsInOrderSkippingBlanks() {
        assertEquals("제목\n요약\n사실1\n사실2\nA, B",
                EmbeddingTextBuilder.build("제목", "요약", List.of("사실1", " ", "사실2"), List.of("A", "B")));
    }

    @Test
    void nullsAreTolerated() {
        assertEquals("제목", EmbeddingTextBuilder.build("제목", null, null, null));
    }

    @Test
    void overlongTextIsCut() {
        assertTrue(EmbeddingTextBuilder.build("x".repeat(10000), null, null, null).length() <= 6000);
    }
}
