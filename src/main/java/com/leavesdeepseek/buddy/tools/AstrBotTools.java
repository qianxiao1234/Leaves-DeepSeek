package com.leavesdeepseek.buddy.tools;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.ai.AgentTool;
import com.leavesdeepseek.buddy.ai.Schema;
import com.leavesdeepseek.buddy.ai.Tools;
import com.leavesdeepseek.buddy.util.Json;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 对接 AstrBot 的咨询工具。
 */
public final class AstrBotTools {

    private final LeavesDeepSeekPlugin plugin;

    public AstrBotTools(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public List<AgentTool> all() {
        return List.of(askAstrBot());
    }

    private AgentTool askAstrBot() {
        Schema schema = Schema.object()
                .str("question", "要问 AstrBot 的问题，尽量具体")
                .require("question");
        AgentTool tool = Tools.of("ask_astrbot",
                "向服务器接入的 AstrBot 咨询知识类问题（服务器规则、玩法机制、活动信息等）。"
                        + "AstrBot 未配置时会返回不可用。",
                schema, (context, arguments) -> {
                    if (!plugin.astrBot().enabled(context.owner())) {
                        return done(Json.error("你还没有配置 AstrBot，无法咨询；可以先用 /buddy astrbot set 设置"));
                    }
                    String question = Json.getString(arguments, "question", "").trim();
                    if (question.isEmpty()) {
                        return done(Json.error("问题不能为空"));
                    }
                    return plugin.astrBot().ask(context.owner(), question)
                            .thenApply(reply -> Json.ok("AstrBot 回复：" + reply, "reply", reply))
                            .exceptionally(error -> Json.error("AstrBot 调用失败：" + Json.rootMessage(error)));
                });
        // AstrBot 的读取超时（默认 120 秒）比全局工具超时更长
        return Tools.withTimeout(tool, plugin.settings().astrBotTimeoutSeconds() + 5);
    }

    private CompletableFuture<String> done(String value) {
        return CompletableFuture.completedFuture(value);
    }
}
