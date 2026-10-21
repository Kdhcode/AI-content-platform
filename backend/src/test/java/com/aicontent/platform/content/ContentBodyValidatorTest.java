package com.aicontent.platform.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicontent.platform.common.BusinessException;
import com.aicontent.platform.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentBodyValidatorTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode parse(String json) throws Exception {
        return M.readTree(json);
    }

    private static List<String> paths(JsonNode body) {
        return ContentBodyValidator.violations(body).stream().map(ContentBodyValidator.Violation::path).toList();
    }

    @Test
    void aWellFormedBodyAndAnEmptyDraftAreAccepted() throws Exception {
        assertThat(paths(parse("""
                {"schemaVersion": 1, "sections": [
                  {"id": "s-1", "heading": "핵심 요약", "paragraphs": [{"id": "p-1", "text": "첫 문단"}, {"id": "p-2", "text": ""}]},
                  {"id": "s-2", "heading": "", "paragraphs": []}]}"""))).isEmpty();
        assertThat(paths(ContentService.emptyBody())).isEmpty();          // drafts may be empty
    }

    @Test
    void structureAndAllowedFieldsAreStrict() throws Exception {
        assertThat(paths(parse("[]"))).containsExactly("body");
        assertThat(paths(parse("{\"schemaVersion\": 2, \"sections\": []}"))).containsExactly("body.schemaVersion");
        assertThat(paths(parse("{\"schemaVersion\": \"1\", \"sections\": []}"))).containsExactly("body.schemaVersion");
        assertThat(paths(parse("{\"schemaVersion\": 1}"))).containsExactly("body.sections");
        assertThat(paths(parse("{\"schemaVersion\": 1, \"sections\": [], \"html\": \"<b>x</b>\"}"))).containsExactly("body.html");
        assertThat(paths(parse("""
                {"schemaVersion": 1, "sections": [{"id": "s-1", "heading": "h", "style": "bold",
                  "paragraphs": [{"id": "p-1", "text": "t", "bold": true}, "plain string"]}]}""")))
                .containsExactly("body.sections[0].style", "body.sections[0].paragraphs[0].bold", "body.sections[0].paragraphs[1]");
        assertThat(paths(parse("{\"schemaVersion\": 1, \"sections\": [{\"id\": \"s-1\", \"paragraphs\": [{\"id\": \"p-1\"}]}]}")))
                .containsExactly("body.sections[0].heading", "body.sections[0].paragraphs[0].text");
    }

    @Test
    void idsMustBeWellFormedAndUniqueAcrossTheWholeBody() throws Exception {
        assertThat(paths(parse("""
                {"schemaVersion": 1, "sections": [
                  {"id": "S 1", "heading": "", "paragraphs": []},
                  {"id": "dup", "heading": "", "paragraphs": [{"id": "dup", "text": ""}, {"text": ""}]}]}""")))
                .containsExactly("body.sections[0].id", "body.sections[1].paragraphs[0].id", "body.sections[1].paragraphs[1].id");
    }

    @Test
    void countAndLengthLimits() {
        ObjectNode body = (ObjectNode) ContentService.emptyBody();
        ArrayNode sections = body.withArray("sections");
        for (int s = 0; s <= ContentBodyValidator.MAX_SECTIONS; s++) {           // one too many
            sections.addObject().put("id", "s-" + s).put("heading", "").putArray("paragraphs");
        }
        assertThat(paths(body)).containsExactly("body.sections");

        ObjectNode one = (ObjectNode) ContentService.emptyBody();
        ObjectNode section = one.withArray("sections").addObject();
        section.put("id", "s-1").put("heading", "가".repeat(ContentBodyValidator.MAX_HEADING_CHARS + 1));
        ArrayNode paragraphs = section.putArray("paragraphs");
        for (int p = 0; p <= ContentBodyValidator.MAX_PARAGRAPHS_PER_SECTION; p++) {
            paragraphs.addObject().put("id", "p-" + p).put("text", "");
        }
        paragraphs.addObject().put("id", "p-long").put("text", "x".repeat(ContentBodyValidator.MAX_PARAGRAPH_CHARS + 1));
        assertThat(paths(one)).containsExactly("body.sections[0].heading", "body.sections[0].paragraphs",
                "body.sections[0].paragraphs[51].text");

        ObjectNode multiline = (ObjectNode) ContentService.emptyBody();
        multiline.withArray("sections").addObject().put("id", "s-1").put("heading", "두 줄\n제목").putArray("paragraphs");
        assertThat(paths(multiline)).containsExactly("body.sections[0].heading");
    }

    @Test
    void theWholeBodyIsCappedAtOneHundredThousandCharacters() {
        ObjectNode body = (ObjectNode) ContentService.emptyBody();
        ArrayNode sections = body.withArray("sections");
        for (int s = 0; s < 21; s++) {                                             // 21 x 5,000 = 105,000
            sections.addObject().put("id", "s-" + s).put("heading", "")
                    .putArray("paragraphs").addObject().put("id", "p-" + s).put("text", "x".repeat(5_000));
        }
        assertThat(paths(body)).containsExactly("body");
    }

    @Test
    void validateReportsCodeAndLocations() throws Exception {
        assertThatThrownBy(() -> ContentBodyValidator.validate(parse("{\"schemaVersion\": 1, \"sections\": {}}")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.errorCode()).isEqualTo(ErrorCode.CONTENT_BODY_INVALID);
                    assertThat(be.details().toString()).contains("body.sections");
                });
        assertThatThrownBy(() -> ContentBodyValidator.validateTitle("x".repeat(201))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ContentBodyValidator.validateTitle("두 줄\n제목")).isInstanceOf(BusinessException.class);
        ContentBodyValidator.validateTitle("");                                    // empty draft title is fine
    }
}
