package com.aicontent.platform.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The prompt files are code: their placeholders must match the variables the services provide. */
class PromptFilesTest {

    private static final Path ROOT = Path.of("src/main/resources/prompts");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private static Set<String> placeholders(Path file) throws IOException {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(Files.readString(file));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void everyPromptVersionHasAllFiles() {
        for (String key : new String[] {"article-analysis", "issue-classifier"}) {
            for (String file : new String[] {"system.md", "user.md", "schema.json", "meta.json"}) {
                assertTrue(Files.isRegularFile(ROOT.resolve(key).resolve("v1").resolve(file)), key + "/v1/" + file + " missing");
            }
        }
    }

    @Test
    void articleAnalysisPlaceholdersMatchServiceVariables() throws IOException {
        // ArticleAnalysisService provides: title, publisher, publishedAt, category, text
        assertEquals(Set.of("category", "publishedAt", "publisher", "text", "title"),
                placeholders(ROOT.resolve("article-analysis/v1/user.md")));
    }

    @Test
    void classifierPlaceholdersAreProvidedByClassifierPromptVariables() throws IOException {
        var vars = ClassifierVarsProbe.keys();
        assertTrue(vars.containsAll(placeholders(ROOT.resolve("issue-classifier/v1/user.md"))));
    }

    @Test
    void systemPromptsHaveNoPlaceholders() throws IOException {
        assertTrue(placeholders(ROOT.resolve("article-analysis/v1/system.md")).isEmpty());
        assertTrue(placeholders(ROOT.resolve("issue-classifier/v1/system.md")).isEmpty());
    }
}
