package com.leavesdeepseek.buddy.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 极简 JSON Schema 构造器，用于描述工具参数。
 */
public final class Schema {

    private final JsonObject root = new JsonObject();
    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    private Schema() {
        root.addProperty("type", "object");
        root.add("properties", properties);
        root.add("required", required);
    }

    public static Schema object() {
        return new Schema();
    }

    public Schema str(String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        properties.add(name, property);
        return this;
    }

    public Schema strEnum(String name, String description, String... values) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        property.add("enum", array);
        properties.add(name, property);
        return this;
    }

    public Schema number(String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "number");
        property.addProperty("description", description);
        properties.add(name, property);
        return this;
    }

    public Schema integer(String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        properties.add(name, property);
        return this;
    }

    public Schema bool(String name, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "boolean");
        property.addProperty("description", description);
        properties.add(name, property);
        return this;
    }

    public Schema require(String... names) {
        for (String name : names) {
            required.add(name);
        }
        return this;
    }

    public JsonObject build() {
        return root.deepCopy();
    }
}
