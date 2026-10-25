package com.leavesdeepseek.buddy.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.bot.BotNames;
import com.leavesdeepseek.buddy.bot.BotSnapshot;
import com.leavesdeepseek.buddy.config.PlayerProfiles;
import com.leavesdeepseek.buddy.config.PluginSettings;
import com.leavesdeepseek.buddy.util.Json;
import com.leavesdeepseek.buddy.util.Tasks;
import org.bukkit.entity.Player;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 负责一次完整的 AI 决策循环：
 * 收集状态 -> 调用 DeepSeek -> 执行工具 -> 回填结果 -> 直到得到最终回复。
 */
public final class AgentService implements AutoCloseable {

    private static final int MAX_TOOL_RESULT_CHARS = 4000;

    private final LeavesDeepSeekPlugin plugin;
    private final PluginSettings settings;
    private final ToolRegistry tools;
    private final DeepSeekClient client;
    private final PlayerProfiles profiles;
    private final AiResolver resolver;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;

    public AgentService(LeavesDeepSeekPlugin plugin, PluginSettings settings, ToolRegistry tools,
                        DeepSeekClient client, PlayerProfiles profiles, AiResolver resolver) {
        this.plugin = plugin;
        this.settings = settings;
        this.tools = tools;
        this.client = client;
        this.profiles = profiles;
        this.resolver = resolver;
    }

    public boolean isBusy(Bot bot) {
        return bot != null && busy.contains(bot.getUniqueId());
    }

    public CompletableFuture<String> ask(Bot bot, Player owner, String userMessage) {
        if (closed) {
            plugin.log().warn("AI", "拒绝：插件正在关闭");
            return CompletableFuture.completedFuture("插件正在关闭，暂时无法行动。");
        }
        String message = userMessage == null ? "" : userMessage.trim();
        if (message.isEmpty()) {
            plugin.log().warn("AI", "拒绝：消息为空");
            return CompletableFuture.completedFuture("你想让我做什么？");
        }

        ResolvedAi resolved = resolver.resolve(owner);
        plugin.log().info("AI", "解析结果 | 玩家=" + (owner == null ? "控制台" : owner.getName())
                + " | 后端=" + resolved.backend()
                + " | 偏好=" + resolved.preference()
                + " | Key来源=" + resolved.keySource().label()
                + " | 已验证=" + resolved.validated()
                + " | 模型=" + (resolved.api() == null ? "-" : resolved.api().model())
                + " | 地址=" + (resolved.api() == null ? "-" : plugin.client().endpointOf(resolved.api()))
                + " | 说明=" + (resolved.reason().isBlank() ? "无" : resolved.reason()));

        if (resolved.needsChoice()) {
            return CompletableFuture.completedFuture(
                    "你同时配置了 DeepSeek 和 AstrBot。请先用 /buddy ai backend deepseek（DeepSeek，可操作假人）"
                            + " 或 /buddy ai backend astrbot（纯对话）选择默认模型。");
        }
        if (!resolved.usable()) {
            String reason = resolved.reason();
            return CompletableFuture.completedFuture(reason == null || reason.isBlank()
                    ? "还没有可用的 AI，请先用 /buddy ai set <apikey> 配置 DeepSeek Key。"
                    : reason);
        }
        if (plugin.bots().isDowned(bot)) {
            return CompletableFuture.completedFuture("我现在倒在地上，先让我复活吧（说「复活」或 /buddy bot revive）。");
        }
        if (!busy.add(bot.getUniqueId())) {
            plugin.log().warn("AI", "拒绝：上一次请求还没结束（bot=" + BotNames.rawName(bot) + "）");
            return CompletableFuture.completedFuture("我还在处理上一个请求，稍等一下~");
        }
        UUID botId = bot.getUniqueId();

        if (resolved.astrBot()) {
            plugin.log().info("AI", "走 AstrBot | 会话配置="
                    + (resolved.astrBotConfig() == null ? "-" : resolved.astrBotConfig().mode()));
            return plugin.astrBot().ask(owner, message)
                    .thenApply(text -> text.replace("\r", "").replace("\n", " ").trim())
                    .exceptionally(error -> {
                        plugin.log().error("AstrBot", "调用失败", error);
                        return "AstrBot 调用失败：" + Json.rootMessage(error);
                    })
                    .whenComplete((reply, error) -> {
                        busy.remove(botId);
                        plugin.log().info("AI", "AstrBot 结束 | 回复长度="
                                + (reply == null ? 0 : reply.length()));
                    });
        }

        final String requestId = nextRequestId();
        plugin.log().info("AI", "请求开始 requestId=" + requestId);
        return resolver.resolveValidated(owner).thenCompose(validated -> {
            if (!validated.deepSeek()) {
                String reason = validated.reason();
                plugin.log().warn("AI", "DeepSeek 不可用：" + reason);
                return CompletableFuture.completedFuture(reason == null || reason.isBlank()
                        ? "DeepSeek 配置不可用，请用 /buddy ai set <apikey> 重新配置。"
                        : reason);
            }
            return Tasks.onMain(plugin, () -> BotSnapshot.capture(bot, owner))
                    .exceptionally(throwable -> {
                        plugin.log().warn("AI", "抓取假人状态失败：" + Json.rootMessage(throwable));
                        return new JsonObject();
                    })
                    .thenCompose(state -> CompletableFuture.supplyAsync(
                            () -> run(bot, owner, message, state, validated.api(), requestId), executor));
        }).whenComplete((reply, throwable) -> {
            busy.remove(botId);
            if (throwable != null) {
                plugin.log().error("AI", "请求结束（异常）requestId=" + requestId, throwable);
            } else {
                plugin.log().info("AI", "请求结束 requestId=" + requestId + " | 回复长度="
                        + (reply == null ? 0 : reply.length()));
            }
            Tasks.onMain(plugin, () -> {
                if (bot.isValid()) {
                    plugin.botStates().save(bot);
                }
                return null;
            });
        });
    }

    private static String nextRequestId() {
        String id = Long.toHexString(System.nanoTime());
        return id.length() > 6 ? id.substring(id.length() - 6) : id;
    }

    private String run(Bot bot, Player owner, String userMessage, JsonObject state, ApiConfig api,
                       String requestId) {
        Session session = sessions.computeIfAbsent(bot.getUniqueId(), key -> new Session());
        String persona = profiles.persona(owner);
        // 记下这次请求的动作世代：主人中途说了新指令（跟随/过来/停下）时，旧请求要立刻收手
        final int actionEpoch = plugin.botActions().epoch(bot);

        JsonArray messages = new JsonArray();
        String finalText = null;
        try {
            messages.add(Json.obj("role", "system", "content",
                    PromptBuilder.systemPrompt(settings, bot, owner, state.toString(),
                            session.recentActionsText(), persona, buildToolGuide(tools.schemas()))));
            for (JsonObject history : session.historySnapshot()) {
                messages.add(history.deepCopy());
            }
            messages.add(Json.obj("role", "user", "content", userMessage));

            // 推理型模型的「加大 max_tokens 重试」每次请求只做一次，避免无限翻倍
            boolean lengthRetried = false;
            String lastFinishReason = null;
            for (int round = 0; round < settings.maxToolRounds(); round++) {
                if (!bot.isValid()) {
                    plugin.log().warn("DeepSeek", "假人已失效，中止。requestId=" + requestId);
                    return "我已经下线了。";
                }
                if (!plugin.botActions().isCurrent(bot, actionEpoch)) {
                    plugin.log().info("DeepSeek", "主人已下达新指令，放弃旧请求 requestId=" + requestId);
                    return "好，我已经按你最新的要求做了。";
                }

                plugin.log().info("DeepSeek", "第 " + (round + 1) + " 轮 | requestId=" + requestId
                        + " | 消息数=" + messages.size()
                        + " | 工具数=" + tools.schemas().size()
                        + " | 模型=" + api.model());
                JsonObject response = client.chat(messages, tools.schemas(), api, requestId).join();
                JsonObject message = extractMessage(response);
                String content = Json.getString(message, "content", "");
                // 推理型本地模型（R1 / QwQ 等）会把 token 花在 reasoning_content 上，
                // max_tokens 太小时 content 为空、finish_reason=length。这里自动加大上限重试一次。
                if ((content == null || content.isBlank())
                        && !lengthRetried
                        && "length".equals(finishReason(response))) {
                    int bigger = Math.min(8192, Math.max(2048, settings.maxTokens() * 2));
                    if (bigger > settings.maxTokens()) {
                        lengthRetried = true;
                        plugin.log().warn("DeepSeek", "输出被 max_tokens 截断（推理占满），用 "
                                + bigger + " 重试一次 | 模型=" + api.model() + " | requestId=" + requestId);
                        plugin.getLogger().warning("[DeepSeek] 模型输出被 max_tokens 截断，已自动用 "
                                + bigger + " 重试（可在 config.yml 调大 deepseek.max-tokens）");
                        response = client.chat(messages, tools.schemas(), api,
                                requestId + "-long", bigger).join();
                        message = extractMessage(response);
                        content = Json.getString(message, "content", "");
                    }
                }
                lastFinishReason = finishReason(response);
                JsonArray toolCalls = message.has("tool_calls") && message.get("tool_calls").isJsonArray()
                        ? message.getAsJsonArray("tool_calls")
                        : null;
                boolean textualToolCall = false;
                if ((toolCalls == null || toolCalls.isEmpty()) && content != null && !content.isBlank()) {
                    // 本地小模型不支持 function calling 时，会把工具调用写成正文 JSON，这里兜住
                    JsonArray salvaged = parseTextToolCalls(content);
                    if (salvaged != null && !salvaged.isEmpty()) {
                        toolCalls = salvaged;
                        textualToolCall = true;
                        content = "";
                        plugin.log().info("DeepSeek", "模型没有走 Function Calling，已从正文解析工具调用"
                                + " | requestId=" + requestId
                                + " | 工具=" + salvaged.get(0).getAsJsonObject()
                                .getAsJsonObject("function").get("name").getAsString());
                    }
                }
                plugin.log().info("DeepSeek", "第 " + (round + 1) + " 轮响应 | requestId=" + requestId
                        + " | 文本长度=" + (content == null ? 0 : content.length())
                        + " | 工具调用=" + (toolCalls == null ? 0 : toolCalls.size()));

                JsonObject assistant = new JsonObject();
                assistant.addProperty("role", "assistant");
                if (textualToolCall) {
                    // 纯文本模型不认识 tool_calls，用自然语言把「刚才调用了什么」写回去
                    assistant.addProperty("content", "（正在执行工具：" + firstToolName(toolCalls) + "）");
                } else {
                    assistant.addProperty("content", content == null ? "" : content);
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        assistant.add("tool_calls", toolCalls.deepCopy());
                    }
                }
                messages.add(assistant);

                if (toolCalls == null || toolCalls.isEmpty()) {
                    finalText = content == null ? "" : content.trim();
                    plugin.log().info("DeepSeek", "本轮无工具调用，作为最终回复，长度=" + finalText.length()
                            + " | requestId=" + requestId);
                    break;
                }

                for (JsonElement element : toolCalls) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    if (!plugin.botActions().isCurrent(bot, actionEpoch)) {
                        plugin.log().info("工具", "主人已下达新指令，跳过旧请求的剩余工具 | requestId=" + requestId);
                        break;
                    }
                    JsonObject call = element.getAsJsonObject();
                    JsonObject function = call.has("function") && call.get("function").isJsonObject()
                            ? call.getAsJsonObject("function")
                            : new JsonObject();
                    String toolName = Json.getString(function, "name", "");
                    String callId = Json.getString(call, "id", "call_" + UUID.randomUUID().toString().substring(0, 8));
                    JsonObject arguments = parseArguments(function.get("arguments"));

                    long startedAt = System.nanoTime();
                    String result;
                    AgentTool tool = tools.get(toolName);
                    if (tool == null) {
                        result = Json.error("未知工具：" + toolName);
                        plugin.log().warn("工具", "未知工具：" + toolName + " | requestId=" + requestId);
                    } else {
                        try {
                            result = tools.execute(tool, new ToolContext(bot, owner, requestId, userMessage), arguments).join();
                        } catch (CompletionException exception) {
                            plugin.log().error("工具", toolName + " 执行异常 | requestId=" + requestId, exception);
                            result = Json.error("执行失败：" + Json.rootMessage(exception));
                        } catch (RuntimeException exception) {
                            plugin.log().error("工具", toolName + " 执行异常 | requestId=" + requestId, exception);
                            result = Json.error("执行失败：" + Json.rootMessage(exception));
                        }
                    }
                    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
                    plugin.log().info("工具", toolName + " 耗时 " + elapsedMs + "ms | requestId=" + requestId
                            + " | 参数=" + Json.truncate(arguments.toString(), 400)
                            + " | 结果=" + Json.truncate(result, 400));

                    session.log(toolName, arguments.toString(), result);
                    if (textualToolCall) {
                        // 纯文本模型：用 user 消息回报结果，避免服务端不认识 role=tool
                        messages.add(Json.obj(
                                "role", "user",
                                "content", "工具 " + toolName + " 返回："
                                        + Json.truncate(result, MAX_TOOL_RESULT_CHARS)));
                    } else {
                        messages.add(Json.obj(
                                "role", "tool",
                                "tool_call_id", callId,
                                "content", Json.truncate(result, MAX_TOOL_RESULT_CHARS)));
                    }

                    if (settings.debug()) {
                        plugin.getLogger().info("[DeepSeek/" + requestId + "] 工具 " + toolName
                                + " " + Json.truncate(arguments.toString(), 300)
                                + " -> " + Json.truncate(result, 500));
                    } else if (settings.chatLogToConsole()) {
                        plugin.getLogger().info("[AI工具] " + toolName
                                + " " + Json.truncate(arguments.toString(), 180)
                                + " -> " + Json.truncate(result, 260));
                    }
                }
            }

            if (finalText == null || finalText.isBlank()) {
                if ("length".equals(lastFinishReason)) {
                    plugin.log().warn("DeepSeek", "模型输出仍被 max_tokens 截断，放弃 summarize | requestId=" + requestId);
                    finalText = "模型的输出被 max_tokens 截断了（推理内容占用了全部额度）。"
                            + "可以让服主调大 config.yml 的 deepseek.max-tokens，或换一个支持工具调用的模型。";
                } else {
                    plugin.log().info("DeepSeek", "达到工具轮次上限或无文本，改走 summarize | requestId=" + requestId);
                    finalText = summarize(bot, messages, api, requestId);
                }
            }
            if (finalText == null || finalText.isBlank()) {
                plugin.log().warn("DeepSeek", "summarize 也没拿到内容，使用兜底文本 | requestId=" + requestId);
                finalText = "我这边已经处理完了，还有什么需要我做的吗？";
            }

            session.remember(userMessage, finalText);
            return finalText;
        } catch (CompletionException exception) {
            plugin.log().error("DeepSeek", "请求失败 | requestId=" + requestId, exception);
            plugin.getLogger().warning("[DeepSeek/" + requestId + "] 请求失败: " + Json.rootMessage(exception));
            return "调用 DeepSeek 失败：" + Json.rootMessage(exception);
        } catch (RuntimeException exception) {
            plugin.log().error("DeepSeek", "处理失败 | requestId=" + requestId, exception);
            plugin.getLogger().warning("[DeepSeek/" + requestId + "] 处理失败: " + Json.rootMessage(exception));
            return "处理请求时出错：" + Json.rootMessage(exception);
        }
    }

    private String summarize(Bot bot, JsonArray messages, ApiConfig api, String requestId) {
        try {
            if (!bot.isValid()) {
                return "我已经下线了。";
            }
            JsonArray copy = messages.deepCopy();
            copy.add(Json.obj("role", "user", "content", "请用一两句中文告诉主人你刚才完成了什么，不要调用工具。"));
            JsonObject response = client.chat(copy, null, api, requestId).join();
            JsonObject message = extractMessage(response);
            return Json.getString(message, "content", "").trim();
        } catch (RuntimeException exception) {
            plugin.log().warn("DeepSeek", "summarize 失败：" + Json.rootMessage(exception)
                    + " | requestId=" + requestId);
            return null;
        }
    }

    /**
     * 把工具 schema 压成一份「工具名(参数)：说明」的速查表，塞进系统提示词。
     * 本地部署的小模型看不到工具语义时最容易漏调用，这份表就是给它看的说明书。
     */
    private static String buildToolGuide(JsonArray schemas) {
        if (schemas == null || schemas.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonElement element : schemas) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("function") || !entry.get("function").isJsonObject()) {
                continue;
            }
            JsonObject function = entry.getAsJsonObject("function");
            String name = Json.getString(function, "name", "");
            if (name.isBlank()) {
                continue;
            }
            String description = Json.getString(function, "description", "");
            List<String> required = new ArrayList<>();
            List<String> optional = new ArrayList<>();
            JsonArray requiredArray = null;
            if (function.has("parameters") && function.get("parameters").isJsonObject()) {
                JsonObject parameters = function.getAsJsonObject("parameters");
                if (parameters.has("required") && parameters.get("required").isJsonArray()) {
                    requiredArray = parameters.getAsJsonArray("required");
                }
                if (parameters.has("properties") && parameters.get("properties").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> property
                            : parameters.getAsJsonObject("properties").entrySet()) {
                        if (isRequired(requiredArray, property.getKey())) {
                            required.add(property.getKey() + "*");
                        } else {
                            optional.add(property.getKey());
                        }
                    }
                }
            }
            List<String> arguments = new ArrayList<>(required);
            arguments.addAll(optional);
            builder.append("- ").append(name).append('(')
                    .append(arguments.isEmpty() ? "" : String.join(", ", arguments))
                    .append(")：").append(description).append('\n');
        }
        return builder.toString().trim();
    }

    private static boolean isRequired(JsonArray required, String name) {
        if (required == null) {
            return false;
        }
        for (JsonElement element : required) {
            if (element.isJsonPrimitive() && name.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 容错解析：有些模型（尤其是本地部署的）不会走 function calling，
     * 而是把调用写成正文里的 JSON，例如：
     * <pre>{"name":"chop_tree","arguments":{}}</pre>
     * 这里把它转换成标准的 tool_calls 结构，后面的流程完全一致。
     *
     * @return 解析出的工具调用；没有则返回 null
     */
    private static String firstToolName(JsonArray toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty() || !toolCalls.get(0).isJsonObject()) {
            return "?";
        }
        JsonObject call = toolCalls.get(0).getAsJsonObject();
        JsonObject function = call.has("function") && call.get("function").isJsonObject()
                ? call.getAsJsonObject("function")
                : new JsonObject();
        return Json.getString(function, "name", "?");
    }

    private JsonArray parseTextToolCalls(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonObject call = Json.parse(content.substring(start, end + 1));
        if (call == null || call.isEmpty()) {
            return null;
        }
        String name = Json.getString(call, "name", "");
        JsonElement arguments = call.get("arguments");
        if (name.isBlank() && call.has("function") && call.get("function").isJsonObject()) {
            JsonObject function = call.getAsJsonObject("function");
            name = Json.getString(function, "name", "");
            arguments = function.get("arguments");
        }
        if (name.isBlank()) {
            name = Json.getString(call, "tool", "");
        }
        if (name.isBlank()) {
            return null;
        }
        if (arguments == null || arguments.isJsonNull()) {
            if (call.has("args")) {
                arguments = call.get("args");
            } else if (call.has("parameters")) {
                arguments = call.get("parameters");
            }
        }
        JsonObject function = new JsonObject();
        function.addProperty("name", name.trim());
        function.addProperty("arguments", arguments == null || arguments.isJsonNull()
                ? "{}"
                : (arguments.isJsonPrimitive() ? arguments.getAsString() : arguments.toString()));
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("id", "call_" + UUID.randomUUID().toString().substring(0, 8));
        toolCall.addProperty("type", "function");
        toolCall.add("function", function);
        JsonArray result = new JsonArray();
        result.add(toolCall);
        return result;
    }

    /** 读取 choices[0].finish_reason（"stop" / "length" / ...）。 */
    private static String finishReason(JsonObject response) {
        if (response == null || !response.has("choices") || !response.get("choices").isJsonArray()) {
            return "";
        }
        JsonArray choices = response.getAsJsonArray("choices");
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
            return "";
        }
        return Json.getString(choices.get(0).getAsJsonObject(), "finish_reason", "");
    }

    private JsonObject extractMessage(JsonObject response) {
        JsonArray choices = response.has("choices") && response.get("choices").isJsonArray()
                ? response.getAsJsonArray("choices")
                : null;
        if (choices == null || choices.isEmpty()) {
            throw new DeepSeekException("模型没有返回任何内容");
        }
        JsonObject choice = choices.get(0).isJsonObject() ? choices.get(0).getAsJsonObject() : null;
        if (choice == null || !choice.has("message") || !choice.get("message").isJsonObject()) {
            throw new DeepSeekException("响应格式不正确");
        }
        return choice.getAsJsonObject("message");
    }

    private JsonObject parseArguments(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return new JsonObject();
        }
        try {
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
            String raw = element.getAsString();
            return Json.parse(raw);
        } catch (RuntimeException exception) {
            return new JsonObject();
        }
    }

    public void clear(UUID botId) {
        sessions.remove(botId);
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        sessions.clear();
        busy.clear();
    }

    private final class Session {

        private final List<JsonObject> history = new ArrayList<>();
        private final Deque<String> actions = new ArrayDeque<>();

        synchronized List<JsonObject> historySnapshot() {
            return new ArrayList<>(history);
        }

        synchronized void remember(String userMessage, String assistantMessage) {
            history.add(Json.obj("role", "user", "content", userMessage));
            history.add(Json.obj("role", "assistant", "content", assistantMessage));
            int limit = Math.max(0, settings.historyLimit());
            while (history.size() > limit) {
                history.remove(0);
            }
        }

        synchronized void log(String tool, String arguments, String result) {
            String line = tool + " " + Json.truncate(arguments, 200) + " -> " + Json.truncate(result, 200);
            actions.addLast(line);
            while (actions.size() > 8) {
                actions.removeFirst();
            }
        }

        synchronized String recentActionsText() {
            if (actions.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (String line : actions) {
                builder.append("- ").append(line).append('\n');
            }
            return builder.toString().trim();
        }
    }
}
