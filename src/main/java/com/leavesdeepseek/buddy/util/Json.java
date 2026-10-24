package com.leavesdeepseek.buddy.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Collection;

/**
 * 轻量的 Gson 辅助方法，统一工具返回值格式。
 */
public final class Json {

    private Json() {
    }

    public static JsonObject obj() {
        return new JsonObject();
    }

    public static JsonObject obj(Object... keyValues) {
        JsonObject result = new JsonObject();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result.add(String.valueOf(keyValues[i]), element(keyValues[i + 1]));
        }
        return result;
    }

    public static JsonElement element(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof JsonElement jsonElement) {
            return jsonElement;
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof Collection<?> collection) {
            JsonArray array = new JsonArray();
            for (Object item : collection) {
                array.add(element(item));
            }
            return array;
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    public static JsonArray array() {
        return new JsonArray();
    }

    /**
     * 成功结果：{"ok":true,"message":"...", ...额外字段}
     */
    public static String ok(String message, Object... extra) {
        JsonObject result = obj(extra);
        result.addProperty("ok", true);
        if (message != null && !message.isBlank()) {
            result.addProperty("message", message);
        }
        return result.toString();
    }

    /**
     * 失败结果：{"ok":false,"error":"..."}
     */
    public static String error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("error", message == null || message.isBlank() ? "未知错误" : message);
        return result.toString();
    }

    public static JsonObject parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(raw);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ex) {
            return new JsonObject();
        }
    }

    public static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...(已截断)";
    }

    public static String getString(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    public static double getDouble(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    public static int getInt(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    public static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
