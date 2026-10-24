package com.leavesdeepseek.buddy.ai;

import com.google.gson.JsonObject;
import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.util.Json;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AstrBot OpenAPI 客户端。
 *
 * <p>不是 OpenAI 协议：{@code POST /api/v1/chat} 请求体是
 * {@code {"username":..., "session_id":..., "message":...}}，响应是 {@code text/event-stream}。
 * 这里只拼装 {@code plain} 事件里的增量文本，遇到 {@code end} 结束。</p>
 */
public final class AstrBotClient implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final String CHAT_PATH = "/api/v1/chat";

    private final LeavesDeepSeekPlugin plugin;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private volatile boolean closed;
    private volatile String lastError = "";
    private volatile long lastErrorAt;

    public AstrBotClient(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    /** 最近一次失败原因；成功调用后清空。 */
    public String lastError() {
        return lastError;
    }

    public long lastErrorAt() {
        return lastErrorAt;
    }

    /**
     * 连通性探测：发一条极短的消息，确认地址 / Key / scope 全部正常。
     */
    public CompletableFuture<String> probe(AstrBotConfig config) {
        return ask(config, "mc-plugin", "mc-plugin-test", "连接测试：请回复一个词。");
    }

    public boolean enabled(Player player) {
        return player != null && plugin.profiles().astrBotConfigFor(player).usable();
    }

    public AstrBotConfig configFor(Player player) {
        return plugin.profiles().astrBotConfigFor(player);
    }

    /**
     * 按配置的 session-mode 计算会话 ID；per-message 模式返回 null（不传，AstrBot 自动新建）。
     */
    public String sessionIdFor(AstrBotConfig config, Player player) {
        String mode = config.mode();
        if ("fixed".equals(mode)) {
            String fixed = config.fixedSession();
            return fixed == null || fixed.isBlank() ? "mc-server-main" : fixed.trim();
        }
        if ("per-message".equals(mode)) {
            return null;
        }
        String name = player == null ? "server" : player.getName();
        return "mc-" + name + "-main";
    }

    public CompletableFuture<String> ask(Player player, String message) {
        AstrBotConfig config = configFor(player);
        if (!config.usable()) {
            return CompletableFuture.completedFuture(
                    "AstrBot 还没有配置，先用 /buddy astrbot set <base-url> <api-key> 设置。");
        }
        // 加 mc- 前缀，避免撞上 AstrBot 配置的管理员 ID（会被拒绝）
        String username = player == null ? "mc-server" : "mc-" + player.getName();
        return ask(config, username, sessionIdFor(config, player), message);
    }

    public CompletableFuture<String> ask(AstrBotConfig config, String username, String sessionId, String message) {
        if (config == null || !config.usable()) {
            return CompletableFuture.completedFuture(
                    "AstrBot 还没有配置，先用 /buddy astrbot set <base-url> <api-key> 设置。");
        }
        if (closed) {
            return CompletableFuture.completedFuture("插件正在关闭，暂时无法连接 AstrBot。");
        }
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return CompletableFuture.completedFuture("要问 AstrBot 什么？");
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String reply = request(config, username, sessionId, text);
                lastError = "";
                lastErrorAt = 0L;
                return reply;
            } catch (IOException | RuntimeException first) {
                if (!retryable(first)) {
                    recordFailure(first);
                    throw asRuntime(first);
                }
                // 5xx / 超时最多重试 1 次；401/403 不重试
                try {
                    String reply = request(config, username, sessionId, text);
                    lastError = "";
                    lastErrorAt = 0L;
                    return reply;
                } catch (IOException | RuntimeException second) {
                    recordFailure(second);
                    throw asRuntime(second);
                }
            }
        }, executor);
    }

    private void recordFailure(Throwable throwable) {
        String message = throwable.getMessage();
        lastError = message == null ? throwable.getClass().getSimpleName() : message;
        lastErrorAt = System.currentTimeMillis();
    }

    private static boolean retryable(Throwable throwable) {
        if (throwable instanceof IOException) {
            return true;
        }
        if (throwable instanceof DeepSeekException) {
            String message = throwable.getMessage();
            return message != null && message.contains("HTTP 5");
        }
        return false;
    }

    private static RuntimeException asRuntime(Throwable throwable) {
        if (throwable instanceof RuntimeException runtime) {
            return runtime;
        }
        return new DeepSeekException("连接 AstrBot 失败：" + throwable.getMessage());
    }

    private String request(AstrBotConfig config, String username, String sessionId, String message) throws IOException {
        String endpoint = config.baseUrl() + CHAT_PATH;
        if (plugin.settings().debug()) {
            plugin.getLogger().info("[AstrBot] POST " + endpoint
                    + " username=" + username + " session=" + sessionId);
        }

        JsonObject body = new JsonObject();
        body.addProperty("username", username == null || username.isBlank() ? "mc-bot" : username);
        if (sessionId != null && !sessionId.isBlank()) {
            body.addProperty("session_id", sessionId);
        }
        body.addProperty("message", message);
        body.addProperty("enable_streaming", false);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        plugin.log().info("AstrBot", "POST " + endpoint
                + " | username=" + username
                + " | session=" + (sessionId == null ? "(自动)" : sessionId)
                + " | mode=" + config.mode()
                + " | key=" + config.maskedKey()
                + " | readTimeout=" + config.timeoutSeconds() + "s"
                + " | body=" + payload.length + " 字节");
        plugin.log().payload("AstrBot", "请求体", body.toString());
        final long sentAt = System.nanoTime();

        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
        } catch (IOException exception) {
            plugin.log().error("AstrBot", "地址无效：" + endpoint, exception);
            throw new IOException("AstrBot 地址无效：" + endpoint + "（" + exception.getMessage() + "）", exception);
        }
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(config.timeoutSeconds() * 1000);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("X-API-Key", config.key());

            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            int code;
            try {
                code = connection.getResponseCode();
            } catch (IOException exception) {
                plugin.log().error("AstrBot", "无法连接 " + endpoint + "（耗时 "
                        + (System.nanoTime() - sentAt) / 1_000_000L + "ms）", exception);
                throw new IOException("无法连接 AstrBot（" + endpoint + "）：" + exception.getMessage()
                        + "。请确认 AstrBot 已启动、地址端口正确、防火墙放行。", exception);
            }
            plugin.log().info("AstrBot", "HTTP " + code + " | 耗时 "
                    + (System.nanoTime() - sentAt) / 1_000_000L + "ms | " + endpoint);
            switch (code) {
                case 401 -> throw new DeepSeekException(
                        "AstrBot 鉴权失败（HTTP 401）：API Key 错误或已吊销（在 AstrBot WebUI → 设置 → OpenAPI 重新创建）");
                case 403 -> throw new DeepSeekException(
                        "AstrBot 权限不足（HTTP 403）：请确认 Key 勾选了 chat scope");
                case 404 -> throw new DeepSeekException(
                        "AstrBot 接口不存在（HTTP 404）：请确认 base-url 只填到端口，例如 http://127.0.0.1:6185，"
                                + "并确认已在 AstrBot 中启用 OpenAPI 插件");
                case 429 -> throw new DeepSeekException("AstrBot 限流（HTTP 429）：请求过于频繁，稍后再试");
                default -> {
                    if (code >= 500) {
                        throw new DeepSeekException("AstrBot 服务端异常（HTTP " + code + "）："
                                + readBody(connection.getErrorStream()));
                    }
                    if (code >= 400) {
                        throw new DeepSeekException("AstrBot 返回 HTTP " + code + "："
                                + readBody(connection.getErrorStream()));
                    }
                }
            }

            String contentType = connection.getContentType();
            plugin.log().info("AstrBot", "Content-Type=" + contentType);
            if (contentType != null && contentType.contains("application/json")) {
                String raw = readBody(connection.getInputStream());
                plugin.log().payload("AstrBot", "响应体（JSON）", raw);
                JsonObject json = Json.parse(raw);
                if ("error".equalsIgnoreCase(Json.getString(json, "status", ""))) {
                    throw new DeepSeekException("AstrBot 返回错误：" + Json.getString(json, "message", raw));
                }
                String direct = Json.getString(json, "message", "");
                return finishReply(direct, config);
            }

            StringBuilder text = new StringBuilder();
            StringBuilder others = new StringBuilder();
            int limit = Math.max(256, config.maxReplyChars() * 4);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean done = false;
                while (!done && (line = reader.readLine()) != null) {
                    if (text.length() >= limit) {
                        break;
                    }
                    if (line.isEmpty() || line.charAt(0) == ':') {
                        continue; // 空行与 ": heartbeat"
                    }
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) {
                        // HTTP 200 也可能直接返回 JSON 错误体
                        if (others.length() < 2048) {
                            others.append(trimmed);
                        }
                        continue;
                    }
                    String raw = trimmed.substring(5).trim();
                    if (raw.isEmpty() || "[DONE]".equals(raw)) {
                        continue;
                    }
                    JsonObject event;
                    try {
                        event = Json.parse(raw);
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                    String type = Json.getString(event, "type", "");
                    switch (type) {
                        case "plain" -> text.append(Json.getString(event, "data", ""));
                        case "error" -> throw new DeepSeekException(
                                "AstrBot 运行出错：" + Json.getString(event, "data", "unknown"));
                        case "end" -> done = true;
                        default -> {
                            // session_id / complete / image / agent_stats / message_saved 等忽略
                        }
                    }
                }
            }
            if (text.length() == 0 && others.length() > 0) {
                plugin.log().warn("AstrBot", "SSE 中没有 plain 事件，原始内容：" + others);
                JsonObject json = null;
                try {
                    json = Json.parse(others.toString());
                } catch (RuntimeException ignored) {
                    // 不是 JSON，保持原样返回提示
                }
                if (json != null) {
                    if ("error".equalsIgnoreCase(Json.getString(json, "status", ""))) {
                        throw new DeepSeekException("AstrBot 返回错误："
                                + Json.getString(json, "message", others.toString()));
                    }
                    String direct = Json.getString(json, "message", "");
                    if (!direct.isEmpty()) {
                        return finishReply(direct, config);
                    }
                }
            }
            return finishReply(text.toString(), config);
        } catch (RuntimeException exception) {
            plugin.log().error("AstrBot", "请求异常：" + exception.getMessage(), exception);
            throw exception;
        } finally {
            connection.disconnect();
        }
    }

    private String finishReply(String raw, AstrBotConfig config) {
        String reply = raw == null ? "" : raw.replace("\r", "").trim();
        int max = config.maxReplyChars();
        if (reply.length() > max) {
            reply = reply.substring(0, max) + "…";
        }
        return reply.isEmpty() ? "（AstrBot 没有返回内容）" : reply;
    }

    private String readBody(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[1024];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > 4096) {
                    break;
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            return "";
        }
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
    }
}
