package com.aicontent.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StubEmbeddingMathTest {

    @Test
    void deterministicAndUnitLength() {
        float[] a = StubEmbeddingMath.embed("가상시 폭우 피해 확산", 64);
        float[] b = StubEmbeddingMath.embed("가상시 폭우 피해 확산", 64);
        assertEquals(64, a.length);
        assertEquals(1.0, StubEmbeddingMath.cosine(a, b), 1e-6);
    }

    @Test
    void sharedTokensAreCloserThanDisjoint() {
        float[] base = StubEmbeddingMath.embed("가상시 폭우 피해 확산 복구", 256);
        float[] near = StubEmbeddingMath.embed("가상시 폭우 피해 복구 지원", 256);
        float[] far = StubEmbeddingMath.embed("전혀 다른 주제 실적 발표 분기", 256);
        assertTrue(StubEmbeddingMath.cosine(base, near) > StubEmbeddingMath.cosine(base, far));
    }
}
