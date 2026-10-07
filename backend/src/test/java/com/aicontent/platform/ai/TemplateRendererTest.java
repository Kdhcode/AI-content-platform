package com.aicontent.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    @Test
    void substitutesAndKeepsDollarsAndBackslashes() {
        assertEquals("제목: $1 \\n 끝", TemplateRenderer.render("제목: {{ t }} 끝", Map.of("t", "$1 \\n")));
    }

    @Test
    void missingVariableIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> TemplateRenderer.render("{{x}}", Map.of()));
    }

    @Test
    void nullValueRendersEmpty() {
        var m = new java.util.HashMap<String, Object>();
        m.put("x", null);
        assertEquals("[]", TemplateRenderer.render("[{{x}}]", m));
    }
}
