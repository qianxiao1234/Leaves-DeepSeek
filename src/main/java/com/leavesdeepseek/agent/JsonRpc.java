package com.leavesdeepseek.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JSON-RPC 2.0 的组包/拆包与错误码。
 *
 * <p>推送帧为了省带宽不带 {@code "jsonrpc":"2.0"}（见规格 §6.2）。</p>
 */
public final class JsonRpc {

    public static final int AUTH_FAILED = -32001;
    public static final int BAD_REQUEST = -32002;
    public static final int RATE_LIMITED = -32003;
    public static final int NOT_ATTACHED = -32004;
    public static final int NO_PATH = -32010;
    public static final int BLOCKED = -32011;
    public static final int TIMEOUT = -32012;
    public static final int OUT_OF_RANGE = -32013;
    public static final int NOT_HOLDING = -32014;
    public static final int DENIED = -32015;
    public static final int BUSY = -32016;
    public static final int NOT_FOUND = -32017;
    public static final int BODY_OFFLINE = -32018;
    public static final int TPS_GUARD = -32019;
    public static final int SPECTATOR = -32020;
    public static final int INTERNAL = -32021;

    private JsonRpc() {
    }

    /** 服务端内部抛出的 RPC 错误，会被 AgentServer 转成 JSON-RPC error 帧。 */
    public static final class RpcException extends RuntimeException {
        private final int code;
        private final JsonObject data;

        public RpcException(int code, String message) {
            this(code, message, null);
        }

        public RpcException(int code, String message, JsonObject data) {
            super(message == null || message.isBlank() ? nameOf(code) : message);
            this.code = code;
            this.data = data;
        }

        public int code() {
            return code;
        }

        public JsonObject data() {
            return data;
        }
    }

    public static JsonObject parse(String raw) {
        JsonElement element = JsonParser.parseString(raw);
        if (!element.isJsonObject()) {
            throw new RpcException(BAD_REQUEST, "帧必须是 JSON 对象");
        }
        return element.getAsJsonObject();
    }

    public static JsonObject object(Object... keyValues) {
        JsonObject object = new JsonObject();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = String.valueOf(keyValues[i]);
            Object value = keyValues[i + 1];
            object.add(key, toJson(value));
        }
        return object;
    }

    public static JsonElement toJson(Object value) {
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (value instanceof JsonElement element) {
            return element;
        }
        if (value instanceof Number number) {
            return new com.google.gson.JsonPrimitive(number);
        }
        if (value instanceof Boolean bool) {
            return new com.google.gson.JsonPrimitive(bool);
        }
        return new com.google.gson.JsonPrimitive(String.valueOf(value));
    }

    public static String response(JsonElement id, JsonElement result) {
        JsonObject frame = new JsonObject();
        frame.addProperty("jsonrpc", "2.0");
        frame.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        if (result == null) {
            frame.add("result", new JsonObject());
        } else {
            frame.add("result", result);
        }
        return frame.toString();
    }

    public static String error(JsonElement id, int code, String message, JsonObject data) {
        JsonObject frame = new JsonObject();
        frame.addProperty("jsonrpc", "2.0");
        frame.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message == null || message.isBlank() ? nameOf(code) : message);
        if (data != null) {
            error.add("data", data);
        }
        frame.add("error", error);
        return frame.toString();
    }

    /** 服务端主动推送（不带 jsonrpc 字段）。 */
    public static String notify(String method, JsonObject params) {
        JsonObject frame = new JsonObject();
        frame.addProperty("method", method);
        frame.add("params", params == null ? new JsonObject() : params);
        return frame.toString();
    }

    public static String nameOf(int code) {
        return switch (code) {
            case AUTH_FAILED -> "AUTH_FAILED";
            case BAD_REQUEST -> "BAD_REQUEST";
            case RATE_LIMITED -> "RATE_LIMITED";
            case NOT_ATTACHED -> "NOT_ATTACHED";
            case NO_PATH -> "NO_PATH";
            case BLOCKED -> "BLOCKED";
            case TIMEOUT -> "TIMEOUT";
            case OUT_OF_RANGE -> "OUT_OF_RANGE";
            case NOT_HOLDING -> "NOT_HOLDING";
            case DENIED -> "DENIED";
            case BUSY -> "BUSY";
            case NOT_FOUND -> "NOT_FOUND";
            case BODY_OFFLINE -> "BODY_OFFLINE";
            case TPS_GUARD -> "TPS_GUARD";
            case SPECTATOR -> "SPECTATOR";
            default -> "INTERNAL";
        };
    }
}
