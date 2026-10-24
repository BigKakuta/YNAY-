package io.ynay.action;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Compiled description of an action input type. Derives the JSON Schema published to clients
 * and performs the structural validation (unknown / missing / null fields, object and array
 * shapes) that Jackson does not report well. Scalar formats are left to Jackson.
 */
sealed interface InputModel {

    JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** JSON Schema for this type. A fresh tree on every call. */
    ObjectNode schema();

    /** Short human phrase for error messages, e.g. "a UUID". */
    String expected();

    /** Appends structural problems of {@code node} (never null here) to {@code errors}. */
    default void validate(JsonNode node, String path, List<String> errors) {
    }

    /** Schema of an action without parameters: an empty object. */
    static ObjectNode emptyObjectSchema() {
        ObjectNode schema = NODES.objectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * Builds the model for an action input record.
     *
     * @throws IllegalArgumentException if the record uses an unsupported component type
     */
    static RecordModel forRecord(Class<?> recordType) {
        if (!recordType.isRecord()) {
            throw new IllegalArgumentException(recordType.getName() + " is not a record");
        }
        return (RecordModel) of(recordType, recordType.getSimpleName(), new HashSet<>());
    }

    private static InputModel of(Type type, String where, Set<Class<?>> visiting) {
        if (type instanceof WildcardType wildcard && wildcard.getUpperBounds().length == 1) {
            return of(wildcard.getUpperBounds()[0], where, visiting);
        }
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() == List.class
                    || parameterized.getRawType() == Collection.class
                    || parameterized.getRawType() == Set.class) {
                Type element = parameterized.getActualTypeArguments()[0];
                return new ListModel(of(element, where + "[]", visiting),
                        parameterized.getRawType() == Set.class);
            }
            throw unsupported(type, where);
        }
        if (!(type instanceof Class<?> cls)) {
            throw unsupported(type, where);
        }
        if (cls == String.class) {
            return new ScalarModel("string", null, null, "a string");
        }
        if (cls == UUID.class) {
            return new ScalarModel("string", "uuid", null, "a UUID");
        }
        if (cls == boolean.class || cls == Boolean.class) {
            return new ScalarModel("boolean", null, null, "a boolean");
        }
        if (cls == int.class || cls == Integer.class || cls == long.class || cls == Long.class
                || cls == short.class || cls == Short.class) {
            return new ScalarModel("integer", null, null, "an integer");
        }
        if (cls == double.class || cls == Double.class || cls == float.class || cls == Float.class) {
            return new ScalarModel("number", null, null, "a number");
        }
        if (cls == LocalDate.class) {
            return new ScalarModel("string", "date", null, "a date (YYYY-MM-DD)");
        }
        if (cls == YearMonth.class) {
            return new ScalarModel("string", null, "^\\d{4}-\\d{2}$", "a month (YYYY-MM)");
        }
        if (cls.isEnum()) {
            List<String> names = Arrays.stream(cls.getEnumConstants()).map(c -> ((Enum<?>) c).name()).toList();
            return new EnumModel(names);
        }
        if (cls.isRecord()) {
            if (!visiting.add(cls)) {
                throw new IllegalArgumentException("Recursive input record " + cls.getName() + " at " + where);
            }
            List<FieldModel> fields = new ArrayList<>();
            for (RecordComponent component : cls.getRecordComponents()) {
                ActionParam param = component.getAnnotation(ActionParam.class);
                boolean required = param == null || param.required();
                String description = param == null || param.value().isBlank() ? null : param.value();
                InputModel model = of(component.getGenericType(), where + "." + component.getName(), visiting);
                fields.add(new FieldModel(component.getName(), description, required,
                        component.getType().isPrimitive(), model));
            }
            visiting.remove(cls);
            return new RecordModel(cls, fields);
        }
        throw unsupported(type, where);
    }

    private static IllegalArgumentException unsupported(Type type, String where) {
        return new IllegalArgumentException("Unsupported action input type " + type.getTypeName() + " at " + where
                + " (supported: String, UUID, boolean, int, long, LocalDate, YearMonth, enums, List<T>, records)");
    }

    /** JSON path segment helper: {@code a} + {@code b} = {@code a.b}, root has an empty path. */
    private static String child(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    record ScalarModel(String jsonType, String format, String pattern, String expected) implements InputModel {
        @Override
        public ObjectNode schema() {
            ObjectNode schema = NODES.objectNode();
            schema.put("type", jsonType);
            if (format != null) {
                schema.put("format", format);
            }
            if (pattern != null) {
                schema.put("pattern", pattern);
            }
            return schema;
        }

        @Override
        public void validate(JsonNode node, String path, List<String> errors) {
            if (node.isContainer()) {
                errors.add("Invalid value for '" + path + "': expected " + expected);
            }
        }
    }

    record EnumModel(List<String> values) implements InputModel {
        @Override
        public ObjectNode schema() {
            ObjectNode schema = NODES.objectNode();
            schema.put("type", "string");
            ArrayNode values = schema.putArray("enum");
            this.values.forEach(values::add);
            return schema;
        }

        @Override
        public String expected() {
            return "one of " + String.join(", ", values);
        }

        @Override
        public void validate(JsonNode node, String path, List<String> errors) {
            if (!node.isString() || !values.contains(node.stringValue())) {
                errors.add("Invalid value for '" + path + "': expected " + expected());
            }
        }
    }

    record ListModel(InputModel element, boolean unique) implements InputModel {
        @Override
        public ObjectNode schema() {
            ObjectNode schema = NODES.objectNode();
            schema.put("type", "array");
            schema.set("items", element.schema());
            if (unique) {
                schema.put("uniqueItems", true);
            }
            return schema;
        }

        @Override
        public String expected() {
            return "an array";
        }

        @Override
        public void validate(JsonNode node, String path, List<String> errors) {
            if (!node.isArray()) {
                errors.add("Invalid value for '" + path + "': expected an array");
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                JsonNode item = node.get(i);
                String itemPath = path + "[" + i + "]";
                if (item.isNull()) {
                    errors.add("Invalid value for '" + itemPath + "': must not be null");
                } else {
                    element.validate(item, itemPath, errors);
                }
            }
        }
    }

    record FieldModel(String name, String description, boolean required, boolean primitive, InputModel type) {
    }

    record RecordModel(Class<?> type, List<FieldModel> fields) implements InputModel {
        @Override
        public ObjectNode schema() {
            ObjectNode schema = NODES.objectNode();
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            List<String> required = new ArrayList<>();
            for (FieldModel field : fields) {
                ObjectNode property = field.type().schema();
                if (field.description() != null) {
                    property.put("description", field.description());
                }
                properties.set(field.name(), property);
                if (field.required()) {
                    required.add(field.name());
                }
            }
            if (!required.isEmpty()) {
                ArrayNode list = schema.putArray("required");
                required.forEach(list::add);
            }
            schema.put("additionalProperties", false);
            return schema;
        }

        @Override
        public String expected() {
            return "an object";
        }

        @Override
        public void validate(JsonNode node, String path, List<String> errors) {
            if (!node.isObject()) {
                errors.add(path.isEmpty()
                        ? "Arguments must be a JSON object"
                        : "Invalid value for '" + path + "': expected an object");
                return;
            }
            Set<String> known = new LinkedHashSet<>();
            for (FieldModel field : fields) {
                known.add(field.name());
                JsonNode value = node.get(field.name());
                String fieldPath = child(path, field.name());
                if (value == null || value.isNull()) {
                    if (field.required()) {
                        errors.add("Missing required parameter '" + fieldPath + "'");
                    }
                } else {
                    field.type().validate(value, fieldPath, errors);
                }
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (!known.contains(property.getKey())) {
                    errors.add("Unknown parameter '" + child(path, property.getKey()) + "'"
                            + (known.isEmpty() ? " (this action takes no parameters)"
                            : " (expected: " + String.join(", ", known) + ")"));
                }
            }
        }
    }
}
