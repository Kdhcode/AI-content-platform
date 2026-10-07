package com.aicontent.platform.ai;

import com.aicontent.platform.common.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The only way LLM output enters the system: call -> extract JSON -> parse -> validate against the prompt's JSON
 * Schema -> (on failure) retry with the violations as a hint -> give up with {@link AiOutputInvalidException}.
 * Every attempt, successful or not, is written to ai_log. Callers never see unvalidated model text.
 *
 * <p>Must not run inside a database transaction: the log rows have to survive a rollback of the caller's work.
 */
@Service
public class StructuredOutputService {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputService.class);

    public record Call(AiCallType type, PromptTemplate prompt, Map<String, Object> variables, int maxAttempts,
                       AiCallContext context) {}

    public record Result(JsonNode value, String model, int attempts) {}

    private final AiClient client;
    private final SchemaValidator validator;
    private final Json json;
    private final AiLogRepository logs;
    private final AiJobRepository aiJobs;

    public StructuredOutputService(AiClient client, SchemaValidator validator, Json json, AiLogRepository logs,
                                   AiJobRepository aiJobs) {
        this.client = client;
        this.validator = validator;
        this.json = json;
        this.logs = logs;
        this.aiJobs = aiJobs;
    }

    public Result execute(Call call) {
        PromptTemplate prompt = call.prompt();
        Long aiJobId = null;
        if (call.context() != null && call.context().asyncJobId() != null) {
            aiJobId = aiJobs.ensure(call.context().asyncJobId(), call.type().name(), call.context().articleId(),
                    prompt.key(), prompt.version(), prompt.schemaVersion());
        }
        Long articleId = call.context() == null ? null : call.context().articleId();
        String baseUser = prompt.renderUser(call.variables());
        List<String> lastErrors = new ArrayList<>();
        int attempts = Math.max(1, call.maxAttempts());

        for (int attempt = 1; attempt <= attempts; attempt++) {
            String user = lastErrors.isEmpty() ? baseUser : baseUser + "\n\n[이전 출력 오류]\n"
                    + String.join("\n", lastErrors) + "\n위 오류를 고쳐 스키마를 정확히 따르는 JSON 객체 하나만 다시 출력하라.";
            AiRequest request = new AiRequest(call.type(), prompt.system(), user, prompt.schema(),
                    prompt.temperature(), call.variables());
            long started = System.nanoTime();
            AiCompletion completion;
            try {
                completion = client.complete(request);
            } catch (AiException e) {
                writeLog(new AiLogRepository.Entry(aiJobId, articleId, call.type(), client.provider(), null,
                        prompt.key(), prompt.version(), attempt, null, null, elapsedMs(started), false, e.code(),
                        e.getMessage(), null));
                throw e;
            }
            int latency = elapsedMs(started);
            if (aiJobId != null && completion.model() != null) {
                final long jobIdForModel = aiJobId;
                safely(() -> aiJobs.setModel(jobIdForModel, completion.model()));
            }

            List<String> errors = new ArrayList<>();
            JsonNode value = parseAndValidate(prompt, completion.text(), errors);
            boolean ok = value != null && errors.isEmpty();
            writeLog(new AiLogRepository.Entry(aiJobId, articleId, call.type(), client.provider(), completion.model(),
                    prompt.key(), prompt.version(), attempt, completion.inputTokens(), completion.outputTokens(), latency,
                    ok, ok ? null : "AI_OUTPUT_INVALID", ok ? null : String.join("; ", errors), completion.text()));
            if (ok) {
                return new Result(value, completion.model(), attempt);
            }
            lastErrors = errors;
        }
        throw new AiOutputInvalidException(
                "LLM output failed schema " + prompt.schemaVersion() + " after " + attempts + " attempt(s)", lastErrors);
    }

    private JsonNode parseAndValidate(PromptTemplate prompt, String text, List<String> errors) {
        String candidate = JsonTextExtractor.extractObject(text);
        if (candidate == null) {
            errors.add("출력에서 JSON 객체를 찾을 수 없다");
            return null;
        }
        JsonNode node;
        try {
            node = json.mapper().readTree(candidate);
        } catch (JsonProcessingException e) {
            errors.add("JSON 구문 오류: " + e.getOriginalMessage());
            return null;
        }
        errors.addAll(validator.validate(prompt, node));
        return node;
    }

    private void writeLog(AiLogRepository.Entry entry) {
        safely(() -> logs.insert(entry));
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("ai bookkeeping failed (ignored): {}", e.getMessage());
        }
    }

    private static int elapsedMs(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000);
    }
}
