package io.ynay.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class InputModelTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    enum Kind { CHECKING, SAVINGS }

    record Split(@ActionParam("Split amount") long amount,
                 @ActionParam(value = "Category", required = false) UUID categoryId) {
    }

    record Everything(
            @ActionParam("Name") String name,
            @ActionParam("Id") UUID id,
            @ActionParam(value = "Flag", required = false) boolean flag,
            @ActionParam(value = "Boxed flag", required = false) Boolean boxedFlag,
            int count,
            @ActionParam(value = "Big", required = false) Long big,
            @ActionParam("Day") LocalDate day,
            @ActionParam("Month") YearMonth month,
            @ActionParam("Kind") Kind kind,
            @ActionParam(value = "Tags", required = false) List<String> tags,
            @ActionParam(value = "Splits", required = false) List<Split> splits,
            @ActionParam(value = "Ids", required = false) Set<UUID> ids,
            @ActionParam(value = "Nested", required = false) Split nested) {
    }

    record Unsupported(Map<String, String> map) {
    }

    record Node(Node next) {
    }

    @Test
    void buildsJsonSchemaForAllSupportedTypes() {
        JsonNode schema = InputModel.forRecord(Everything.class).schema();
        JsonNode expected = mapper.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "name": {"type": "string", "description": "Name"},
                    "id": {"type": "string", "format": "uuid", "description": "Id"},
                    "flag": {"type": "boolean", "description": "Flag"},
                    "boxedFlag": {"type": "boolean", "description": "Boxed flag"},
                    "count": {"type": "integer"},
                    "big": {"type": "integer", "description": "Big"},
                    "day": {"type": "string", "format": "date", "description": "Day"},
                    "month": {"type": "string", "pattern": "^\\\\d{4}-\\\\d{2}$", "description": "Month"},
                    "kind": {"type": "string", "enum": ["CHECKING", "SAVINGS"], "description": "Kind"},
                    "tags": {"type": "array", "items": {"type": "string"}, "description": "Tags"},
                    "splits": {"type": "array", "description": "Splits", "items": {
                        "type": "object",
                        "properties": {
                          "amount": {"type": "integer", "description": "Split amount"},
                          "categoryId": {"type": "string", "format": "uuid", "description": "Category"}
                        },
                        "required": ["amount"],
                        "additionalProperties": false
                    }},
                    "ids": {"type": "array", "items": {"type": "string", "format": "uuid"}, "uniqueItems": true, "description": "Ids"},
                    "nested": {
                        "type": "object",
                        "properties": {
                          "amount": {"type": "integer", "description": "Split amount"},
                          "categoryId": {"type": "string", "format": "uuid", "description": "Category"}
                        },
                        "required": ["amount"],
                        "additionalProperties": false,
                        "description": "Nested"
                    }
                  },
                  "required": ["name", "id", "count", "day", "month", "kind"],
                  "additionalProperties": false
                }""");
        assertThat(schema).isEqualTo(expected);
        // property order follows the record
        assertThat(schema.get("properties").propertyNames()).containsExactly(
                "name", "id", "flag", "boxedFlag", "count", "big", "day", "month", "kind", "tags", "splits", "ids", "nested");
    }

    @Test
    void emptySchemaForNoArgActions() {
        assertThat(InputModel.emptyObjectSchema()).isEqualTo(
                mapper.readTree("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
    }

    @Test
    void schemaIsAFreshTreeEveryTime() {
        InputModel.RecordModel model = InputModel.forRecord(Split.class);
        model.schema().put("type", "tampered");
        assertThat(model.schema().get("type").stringValue()).isEqualTo("object");
    }

    @Test
    void rejectsUnsupportedAndRecursiveTypes() {
        assertThatThrownBy(() -> InputModel.forRecord(Unsupported.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported action input type java.util.Map<java.lang.String, java.lang.String>")
                .hasMessageContaining("Unsupported.map");
        assertThatThrownBy(() -> InputModel.forRecord(Node.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Recursive");
        assertThatThrownBy(() -> InputModel.forRecord(String.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesStructure() {
        assertThat(errors("""
                {"name": "x", "id": null, "count": 1, "day": "2026-10-24", "month": "2026-10",
                 "kind": "BOGUS", "extra": 1, "tags": "notalist", "splits": [{"memo": "m"}, null],
                 "nested": 5}""")).containsExactly(
                "Missing required parameter 'id'",
                "Invalid value for 'kind': expected one of CHECKING, SAVINGS",
                "Invalid value for 'tags': expected an array",
                "Missing required parameter 'splits[0].amount'",
                "Unknown parameter 'splits[0].memo' (expected: amount, categoryId)",
                "Invalid value for 'splits[1]': must not be null",
                "Invalid value for 'nested': expected an object",
                "Unknown parameter 'extra' (expected: name, id, flag, boxedFlag, count, big, day, month, kind, tags, splits, ids, nested)");
    }

    @Test
    void acceptsValidInputAndOptionalNulls() {
        assertThat(errors("""
                {"name": "x", "id": "8a5f1c6e-6f43-4a51-9d7e-3f1f0e7c9a10", "count": 1, "day": "2026-10-24",
                 "month": "2026-10", "kind": "SAVINGS", "flag": null, "tags": null,
                 "splits": [{"amount": -1000}]}""")).isEmpty();
    }

    @Test
    void rejectsNonObjectArguments() {
        assertThat(errors("[1,2]")).containsExactly("Arguments must be a JSON object");
    }

    @Test
    void scalarsMustNotBeContainers() {
        assertThat(errors("""
                {"name": {"a": 1}, "id": "8a5f1c6e-6f43-4a51-9d7e-3f1f0e7c9a10", "count": [1], "day": "2026-10-24",
                 "month": "2026-10", "kind": "SAVINGS"}""")).containsExactly(
                "Invalid value for 'name': expected a string",
                "Invalid value for 'count': expected an integer");
    }

    private List<String> errors(String json) {
        List<String> errors = new ArrayList<>();
        InputModel.forRecord(Everything.class).validate(mapper.readTree(json), "", errors);
        return errors;
    }
}
