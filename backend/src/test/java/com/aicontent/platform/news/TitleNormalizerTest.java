package com.aicontent.platform.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class TitleNormalizerTest {

    @Test
    void retaggedTitlesHashEqual() {
        assertEquals(TitleNormalizer.hash("[속보] 가상시 폭우 피해 확산"), TitleNormalizer.hash("가상시 폭우 피해 확산 [종합]"));
    }

    @Test
    void differentTitlesHashDifferent() {
        assertNotEquals(TitleNormalizer.hash("가상시 폭우 피해 확산"), TitleNormalizer.hash("가상시 폭우 피해 복구"));
    }

    @Test
    void caseAndPunctuationIgnored() {
        assertEquals(TitleNormalizer.normalize("Hello,  World!"), TitleNormalizer.normalize("hello world"));
    }
}
