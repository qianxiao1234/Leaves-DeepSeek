package com.leavesdeepseek.buddy.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.config.PluginSettings;
import com.leavesdeepseek.buddy.util.Json;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * OpenAI 兼容协议的对话客户端（DeepSeek 官方 / 任意兼容网关）。
 *
 * <p>重写要点：</p>
 * <ul>
 *   <li>base-url 智能拼接，兼容 {@code https://api.deepseek.com}、{@code .../v1}、
 *       以及误填成 {@code .../chat/completions} 的情况；</li>
 *   <li>按 HTTP 状态码给出可操作的中文提示，而不是只抛 {@code HTTP 401}；</li>
 *   <li>429 / 5xx / 网络超时自动重试一次；401/402/403/404 不重试；</li>
 *   <li>记录最近一次失败原因，供 {@code /buddy ai status} 展示。</li>
 * </ul>
 */
public final class DeepSeekClient {

    private static final String CHAT_PATH = "/chat/completions";

    private final LeavesDeepSeekPlugin plugin;
    private final PluginSettings settings;
    private final HttpClient http;

    private volatile String lastError = "";
    private volatile long lastErrorAt;

    public DeepSeekClient(LeavesDeepSeekPlugin plugin, PluginSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 发起一次对话请求。
     *
     * @param messages  完整消息数组（含 system）
     * @param tools     工具定义；null 或空表示纯文本对话
     * @param api       实际使用的 Key / 模型 / 地址
     * @param requestId 日志标记
     */
    public CompletableFuture<JsonObject> chat(JsonArray messages, JsonArray tools, ApiConfig api, String requestId) {
        return chat(messages, tools, api, requestId, settings.maxTokens());
    }

    /**
     * 同上，但可以单独指定 max_tokens（推理型模型把 token 花在 reasoning 上时，用于自动加大上限重试）。
     */
    public CompletableFuture<JsonObject> chat(JsonArray messages, JsonArray tools, ApiConfig api,
                                              String requestId, int maxTokens) {
        if (api == null || !api.usable()) {
            return failed("没有可用的 API 配置（缺少 api key / model / base-url）");
        }
        if (messages == null || messages.isEmpty()) {
            return failed("消息列表为空");
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", api.model());
        body.add("messages", messages);
        body.addProperty("temperature", settings.temperature());
        body.addProperty("max_tokens", Math.max(16, maxTokens));
        body.addProperty("stream", false);
        if (tools != null && !tools.isEmpty()) {
            body.add("tools", tools);
            body.addProperty("tool_choice", "auto");
        }
        return chatWithToolFallback(body, api, requestId, tools != null && !tools.isEmpty());
    }

    /**
     * 兼容不支持 Function Calling 的模型（常见于本地部署的 OpenAI 兼容服务）：
     * 如果带 tools 的请求被判定为「不支持工具」，就丢掉 tools 重发一次。
     * 提示词里已经包含了工具手册，AgentService 也能解析正文里的 JSON 工具调用。
     */
    private CompletableFuture<JsonObject> chatWithToolFallback(JsonObject body, ApiConfig api,
                                                               String requestId, boolean hasTools) {
        CompletableFuture<JsonObject> first = send(body, api, requestId, 0);
        if (!hasTools) {
            return first;
        }
        return first.handle((json, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(json);
            }
            Throwable cause = unwrap(error);
            if (!(cause instanceof ToolUnsupportedException)) {
                return CompletableFuture.<JsonObject>failedFuture(
                        error instanceof CompletionException ? error : new CompletionException(error));
            }
            JsonObject fallback = body.deepCopy();
            fallback.remove("tools");
            fallback.remove("tool_choice");
            plugin.log().warn("DeepSeek", "模型明确拒绝 tools，改用「正文 JSON 工具调用」模式重试"
                    + " | requestId=" + requestId);
            return send(fallback, api, requestId, 0);
        }).thenCompose(future -> future);
    }

    /**
     * 上游是否在明确拒绝工具参数（而不是普通的 400/404）。
     * <p>LM Studio 的 {@code {"error":"terminated"}}、{@code No engine protocol runtime...}
     * 这类上游错误不能触发降级重试。</p>
     */
    private static boolean looksLikeToolRejection(int status, String body) {
        if (status != 400 && status != 422 && status != 404) {
            return false;
        }
        String lower = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("\"tool") || lower.contains("tool_choice")
                || lower.contains("function_call") || lower.contains("function calling")
                || lower.contains("function is not supported") || lower.contains("functions are not supported")
                || lower.contains("unsupported function") || lower.contains("does not support tools");
    }

    /**
     * 用一条最小请求确认 Key / 模型 / 地址确实可用。
     */
    public CompletableFuture<Void> validate(ApiConfig api) {
        if (api == null || !api.usable()) {
            return failed("API 配置不完整（缺 api key / model / base-url）");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", api.model());
        JsonArray messages = new JsonArray();
        messages.add(Json.obj("role", "user", "content", "ping"));
        body.add("messages", messages);
        body.addProperty("max_tokens", 16);
        body.addProperty("stream", false);

        return send(body, api, "validate", 0).thenApply(json -> {
            JsonArray choices = json.has("choices") && json.get("choices").isJsonArray()
                    ? json.getAsJsonArray("choices")
                    : null;
            if (choices == null || choices.isEmpty()) {
                throw new CompletionException(new DeepSeekException(
                        "模型没有返回内容，请检查 model 是否正确（如 deepseek-chat）"));
            }
            return null;
        });
    }

    /** 最近一次失败原因；成功后清空。 */
    public String lastError() {
        return lastError;
    }

    public long lastErrorAt() {
        return lastErrorAt;
    }

    /** 计算出真正请求的完整地址，供日志与诊断展示。 */
    public String endpointOf(ApiConfig api) {
        return api == null ? "" : resolveEndpoint(api.baseUrl());
    }

    private CompletableFuture<JsonObject> send(JsonObject body, ApiConfig api, String requestId, int attempt) {
        String endpoint = resolveEndpoint(api.baseUrl());
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(settings.requestTimeoutSeconds()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + api.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException exception) {
            return failed("请求地址无效：" + endpoint + "（" + exception.getMessage() + "）");
        }

        if (settings.debug()) {
            plugin.getLogger().info("[DeepSeek/" + requestId + "] POST " + endpoint
                    + " model=" + api.model()
                    + " tools=" + (body.has("tools") ? body.getAsJsonArray("tools").size() : 0)
                    + " body=" + Json.truncate(body.toString(), 1500));
        }
        plugin.log().info("DeepSeek", "POST " + endpoint
                + " | attempt=" + (attempt + 1)
                + " | model=" + api.model()
                + " | key=" + api.maskedKey()
                + " | tools=" + (body.has("tools") ? body.getAsJsonArray("tools").size() : 0)
                + " | body=" + body.toString().length() + " 字符"
                + " | timeout=" + settings.requestTimeoutSeconds() + "s"
                + " | requestId=" + requestId);
        plugin.log().payload("DeepSeek", "请求体 requestId=" + requestId, body.toString());
        final long sentAt = System.nanoTime();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) -> {
                    long elapsedMs = (System.nanoTime() - sentAt) / 1_000_000L;
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        plugin.log().warn("DeepSeek", "网络异常 " + elapsedMs + "ms | "
                                + describeNetwork(cause, endpoint) + " | requestId=" + requestId);
                        if (isRetryable(cause) && attempt < 1) {
                            if (settings.debug()) {
                                plugin.getLogger().info("[DeepSeek/" + requestId + "] 重试一次："
                                        + describeNetwork(cause, endpoint));
                            }
                            plugin.log().info("DeepSeek", "重试一次 | requestId=" + requestId);
                            return send(body, api, requestId, attempt + 1).join();
                        }
                        String message = describeNetwork(cause, endpoint);
                        record(message);
                        plugin.log().error("DeepSeek", "请求失败：" + message + " | requestId=" + requestId, cause);
                        throw new CompletionException(new DeepSeekException(message, cause));
                    }
                    plugin.log().info("DeepSeek", "HTTP " + response.statusCode() + " | " + elapsedMs
                            + "ms | " + endpoint + " | requestId=" + requestId);
                    return handleResponse(response, requestId, endpoint, body.has("tools"));
                });
    }

    private JsonObject handleResponse(HttpResponse<String> response, String requestId, String endpoint,
                                      boolean toolsRequested) {
        int status = response.statusCode();
        String text = response.body() == null ? "" : response.body();
        plugin.log().payload("DeepSeek", "响应体 status=" + status + " requestId=" + requestId, text);

        if (status < 200 || status >= 300) {
            String message = describeStatus(status, text, endpoint);
            record(message);
            plugin.log().error("DeepSeek", "HTTP " + status + " | " + message + " | requestId=" + requestId);
            if (toolsRequested && looksLikeToolRejection(status, text)) {
                // 只有上游明确提到 tools/function 才走「去掉工具重试」，避免把网络/上游错误误判
                throw new CompletionException(new ToolUnsupportedException(message));
            }
            throw new CompletionException(new DeepSeekException(message));
        }

        JsonObject json;
        try {
            json = Json.parse(text);
        } catch (RuntimeException exception) {
            String message = "响应不是合法 JSON（" + endpoint + "）：" + Json.truncate(text, 200);
            record(message);
            plugin.log().error("DeepSeek", message + " | requestId=" + requestId, exception);
            throw new CompletionException(new DeepSeekException(message));
        }
        if (json.has("error") && !json.get("error").isJsonNull()) {
            String detail = extractApiMessage(text);
            String message = "API 返回错误：" + (detail.isEmpty()
                    ? Json.truncate(json.get("error").toString(), 300) : detail);
            record(message);
            plugin.log().error("DeepSeek", message + " | requestId=" + requestId);
            throw new CompletionException(new DeepSeekException(message));
        }

        lastError = "";
        lastErrorAt = 0L;
        if (settings.debug()) {
            plugin.getLogger().info("[DeepSeek/" + requestId + "] 响应: " + Json.truncate(text, 1500));
        }
        return json;
    }

    /**
     * base-url 归一化：
     * <ul>
     *   <li>{@code https://api.deepseek.com} → {@code https://api.deepseek.com/chat/completions}</li>
     *   <li>{@code https://api.deepseek.com/v1} → {@code .../v1/chat/completions}</li>
     *   <li>{@code .../chat/completions}（误填）→ 原样使用</li>
     * </ul>
     */
    static String resolveEndpoint(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.isEmpty()) {
            return "https://api.deepseek.com" + CHAT_PATH;
        }
        if (url.endsWith(CHAT_PATH)) {
            return url;
        }
        return url + CHAT_PATH;
    }

    private static String describeStatus(int status, String body, String endpoint) {
        String detail = extractApiMessage(body);
        String hint = switch (status) {
            case 400 -> "请求被拒绝：参数不合法（常见原因是该模型不支持 Function Calling，可改用 deepseek-chat）";
            case 401 -> "API Key 无效或已失效，请重新执行 /buddy ai set <apikey>";
            case 402 -> "账户余额不足，请到 DeepSeek 开放平台充值";
            case 403 -> "该 Key 没有访问这个模型的权限，请检查 Key 与 model";
            case 404 -> "接口或模型不存在，请检查 base-url（默认 https://api.deepseek.com）和 model";
            case 422 -> "请求参数不合法，可能是模型不支持 tools";
            case 429 -> "请求过于频繁或额度用尽，请稍后重试";
            case 500, 502, 503, 504 -> "服务端暂时不可用（" + status + "），请稍后重试";
            default -> status >= 500 ? "服务端异常（" + status + "）" : "请求失败（" + status + "）";
        };
        StringBuilder builder = new StringBuilder("HTTP ").append(status)
                .append("：").append(hint);
        if (!detail.isEmpty()) {
            builder.append("；详情：").append(Json.truncate(detail, 240));
        }
        builder.append("；地址：").append(endpoint);
        return builder.toString();
    }

    private static String describeNetwork(Throwable cause, String endpoint) {
        if (cause instanceof HttpTimeoutException) {
            return "请求超时：连接 " + endpoint + " 超过设定时间没有响应（检查网络、代理或 base-url）";
        }
        if (cause instanceof UnknownHostException) {
            return "域名解析失败：" + cause.getMessage() + "（检查 base-url 或 DNS）";
        }
        if (cause instanceof ConnectException) {
            return "无法连接 " + endpoint + "：" + cause.getMessage() + "（检查网络、防火墙或代理）";
        }
        if (cause instanceof IOException) {
            return "网络异常：" + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : "（" + cause.getMessage() + "）");
        }
        return "请求失败：" + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
    }

    private static boolean isRetryable(Throwable cause) {
        if (cause instanceof HttpTimeoutException || cause instanceof ConnectException) {
            return true;
        }
        if (cause instanceof DeepSeekException) {
            String message = cause.getMessage();
            if (message == null) {
                return false;
            }
            return message.startsWith("HTTP 429")
                    || message.startsWith("HTTP 500")
                    || message.startsWith("HTTP 502")
                    || message.startsWith("HTTP 503")
                    || message.startsWith("HTTP 504");
        }
        return false;
    }

    /** 从错误响应体里挖出可读的 message。 */
    private static String extractApiMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonObject json = Json.parse(body);
            if (json.has("error") && json.get("error").isJsonObject()) {
                JsonObject error = json.getAsJsonObject("error");
                String message = Json.getString(error, "message", "");
                if (!message.isBlank()) {
                    return message;
                }
            }
            String message = Json.getString(json, "message", "");
            if (!message.isBlank()) {
                return message;
            }
            String detail = Json.getString(json, "detail", "");
            if (!detail.isBlank()) {
                return detail;
            }
        } catch (RuntimeException ignored) {
            // 不是 JSON，退回原文
        }
        return body.trim();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void record(String message) {
        lastError = message == null ? "" : message;
        lastErrorAt = System.currentTimeMillis();
    }

    private <T> CompletableFuture<T> failed(String message) {
        record(message);
        return CompletableFuture.failedFuture(new DeepSeekException(message));
    }

    public void close() {
        try {
            http.shutdownNow();
        } catch (Throwable ignored) {
            // 忽略关闭异常
        }
    }
}
