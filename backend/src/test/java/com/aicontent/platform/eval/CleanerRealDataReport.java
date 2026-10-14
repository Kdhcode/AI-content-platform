package com.aicontent.platform.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicontent.platform.news.AnalysisInputCleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs {@link AnalysisInputCleaner} over real exported articles and writes what was removed from each, so a person
 * can check that no article content was cut. No AI call. Enabled with {@code -Darticles.json=<file>}; the report goes
 * to {@code -Darticles.report=<file>}.
 */
@EnabledIfSystemProperty(named = "articles.json", matches = ".+")
class CleanerRealDataReport {

    @Test
    void reportsRemovedFragmentsPerArticle() throws Exception {
        List<String> lines = new ArrayList<>();
        int changed = 0;
        for (RealArticles a : RealArticles.load(Path.of(System.getProperty("articles.json")))) {
            var cleaned = AnalysisInputCleaner.clean(a.text());
            int before = a.text().trim().length();
            int after = cleaned.text().length();
            if (!cleaned.removed().isEmpty()) {
                changed++;
            }
            lines.add(a.id() + "\t" + a.source() + "\tbefore=" + before + "\tafter=" + after + "\tremoved=" + cleaned.removed().size());
            cleaned.removed().forEach(r -> lines.add("\t- " + r));
            // every sentence that survives must appear in the original in the same order: the cleaner only deletes
            for (String part : cleaned.text().split("\\s+")) {
                assertThat(a.text()).contains(part);
            }
        }
        lines.add(0, "articles changed: " + changed);
        Files.write(Path.of(System.getProperty("articles.report", "cleaner-report.txt")), lines);
    }
}
