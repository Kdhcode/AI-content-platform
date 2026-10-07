package com.aicontent.platform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** JSON Schema (2020-12) validation of LLM output. Compiled schemas are cached per prompt key+version. */
@Component
public class SchemaValidator {

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final Map<String, JsonSchema> cache = new ConcurrentHashMap<>();

    /** @return human readable violations; empty when the value conforms. */
    public List<String> validate(PromptTemplate prompt, JsonNode value) {
        JsonSchema schema = cache.computeIfAbsent(prompt.key() + "/" + prompt.version(),
                k -> factory.getSchema(prompt.schema()));
        return schema.validate(value).stream().map(m -> m.getMessage()).sorted().toList();
    }
}
