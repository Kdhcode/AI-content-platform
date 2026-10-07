package com.aicontent.platform.ai;

import com.aicontent.platform.issue.ClassifierPromptVariables;
import java.util.List;
import java.util.Set;

final class ClassifierVarsProbe {
    private ClassifierVarsProbe() {}

    static Set<String> keys() {
        var article = new ClassifierPromptVariables.ArticleInput(1, "t", "p", null, "s", List.of(), List.of(), null);
        return ClassifierPromptVariables.build(article, List.of()).keySet();
    }
}
