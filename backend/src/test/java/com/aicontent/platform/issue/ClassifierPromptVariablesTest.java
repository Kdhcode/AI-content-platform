package com.aicontent.platform.issue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicontent.platform.ai.TemplateRenderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassifierPromptVariablesTest {

    @Test
    void rendersTheRealPromptTemplateWithoutMissingVariables() throws IOException {
        var article = new ClassifierPromptVariables.ArticleInput(7, "제목", "가상일보", "2026-10-01T09:00+09:00", "요약",
                List.of("사실1", "사실2"), List.of("가상시"), "화재");
        var candidates = List.of(new ClassifierPromptVariables.CandidateInput(10, "이슈 제목", null, List.of(), "a", "b", 2, 2));
        var vars = ClassifierPromptVariables.build(article, candidates);

        String template = Files.readString(Path.of("src/main/resources/prompts/issue-classifier/v1/user.md"));
        String rendered = TemplateRenderer.render(template.trim(), vars);

        assertTrue(rendered.contains("- 사실1\n- 사실2"));
        assertTrue(rendered.contains("- id: 10 | 제목: 이슈 제목 | 요약: - | 핵심 사실: - |"));
        assertFalse(rendered.contains("{{"));
        assertEquals(1, ((List<?>) vars.get("candidatesData")).size());
    }

    @Test
    void similarityIsNotPartOfThePrompt() {
        var article = new ClassifierPromptVariables.ArticleInput(1, "t", "p", null, "s", List.of(), List.of(), null);
        var vars = ClassifierPromptVariables.build(article, List.of());
        assertFalse(vars.keySet().stream().anyMatch(k -> k.toLowerCase().contains("similar")));
        assertEquals("알 수 없음", vars.get("publishedAt"));
    }
}
