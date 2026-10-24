package com.leavesdeepseek.buddy.tools;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.ai.AgentTool;
import com.leavesdeepseek.buddy.ai.Schema;
import com.leavesdeepseek.buddy.ai.Tools;
import com.leavesdeepseek.buddy.bot.BotNames;
import com.leavesdeepseek.buddy.util.Json;
import com.leavesdeepseek.buddy.util.Tasks;
import com.leavesdeepseek.buddy.util.Text;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 交流与等待类工具。
 */
public final class ChatTools {

    private final LeavesDeepSeekPlugin plugin;

    public ChatTools(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public List<AgentTool> all() {
        return List.of(say(), waitTicks());
    }

    private AgentTool say() {
        Schema schema = Schema.object()
                .str("message", "要说的话")
                .bool("broadcast", "是否广播到服务器聊天栏；默认按服务器配置")
                .require("message");
        return Tools.of("say", "在游戏里说一句话。broadcast=false 时只让主人看到。", schema, (context, arguments) -> {
            String message = Json.getString(arguments, "message", "").trim();
            if (message.isEmpty()) {
                return done(Json.error("要说点什么？"));
            }
            boolean broadcast = Json.getBoolean(arguments, "broadcast", plugin.settings().speakInChat());
            if (broadcast) {
                context.bot().chat(message);
            } else {
                Player owner = context.owner();
                if (owner == null || !owner.isOnline()) {
                    return done(Json.error("主人不在线，没有人能听到"));
                }
                Text.send(owner, "§b" + BotNames.rawName(context.bot()) + " §7» §f" + message);
            }
            return done(Json.ok("已经说了"));
        });
    }

    private AgentTool waitTicks() {
        Schema schema = Schema.object()
                .integer("ticks", "等待的 tick 数，1 秒约等于 20 tick，最多 200")
                .require("ticks");
        return Tools.of("wait", "原地等待一段时间，再继续下一步。", schema, (context, arguments) -> {
            int ticks = Math.max(1, Math.min(200, Json.getInt(arguments, "ticks", 20)));
            return Tasks.delay(plugin, ticks).thenApply(ignored -> Json.ok("等待结束"));
        });
    }

    private CompletableFuture<String> done(String value) {
        return CompletableFuture.completedFuture(value);
    }
}
