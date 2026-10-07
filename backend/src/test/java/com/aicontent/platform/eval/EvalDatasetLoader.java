package com.aicontent.platform.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Loads {@code eval/issue-eval-v1.json} from the test classpath. */
public final class EvalDatasetLoader {

    private EvalDatasetLoader() {}

    public static List<EvalCase> load(String resource) throws IOException {
        try (InputStream in = EvalDatasetLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing test resource " + resource);
            }
            JsonNode root = new ObjectMapper().readTree(in);
            List<EvalCase> out = new ArrayList<>();
            for (JsonNode n : root.get("cases")) {
                out.add(new EvalCase(n.get("id").asText(), n.get("publisher").asText(), n.get("publishedAt").asText(),
                        n.get("title").asText(), n.get("summary").asText(), strings(n.get("keyFacts")),
                        strings(n.get("entities")), n.get("eventGroup").asText(), n.get("multiEvent").asBoolean(),
                        n.get("ambiguous").asBoolean(), n.path("note").asText("")));
            }
            return out;
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(x -> out.add(x.asText()));
        return out;
    }
}
