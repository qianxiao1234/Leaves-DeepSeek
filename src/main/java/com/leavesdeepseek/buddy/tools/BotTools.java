package com.leavesdeepseek.buddy.tools;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.ai.AgentTool;
import com.leavesdeepseek.buddy.ai.Schema;
import com.leavesdeepseek.buddy.ai.Tools;
import com.leavesdeepseek.buddy.bot.BotNames;
import com.leavesdeepseek.buddy.util.Json;
import org.bukkit.entity.Player;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 创建 / 移除假人（默认关闭，需要 safety.allow-create-bot）。
 */
public final class BotTools {

    private final LeavesDeepSeekPlugin plugin;

    public BotTools(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public List<AgentTool> all() {
        return List.of(createBot(), removeBot());
    }

    private AgentTool createBot() {
        Schema schema = Schema.object()
                .str("name", "假人名，可省略，默认使用配置的前缀加主人名")
                .str("skin", "皮肤对应的正版玩家名，可省略");
        return Tools.of("create_bot", "再创建一个伙伴假人（需要服务器允许）。", schema, (context, arguments) -> {
            if (!plugin.settings().allowCreateBot()) {
                return done(Json.error("服务器没有允许 AI 自己创建假人"));
            }
            Player owner = context.owner();
            if (owner == null || !owner.isOnline()) {
                return done(Json.error("主人不在线，无法创建"));
            }
            if (!plugin.ai().resolve(owner).usable()) {
                return done(Json.error("主人还没有可用的 AI（DeepSeek Key 或 AstrBot），无法创建新的假人"));
            }
            return plugin.bots().create(owner, Json.getString(arguments, "name", null),
                            Json.getString(arguments, "skin", null))
                    .thenApply(bot -> bot == null
                            ? Json.error("创建假人失败，请检查服务器日志")
                            : Json.ok("创建成功", "bot", BotNames.rawName(bot)));
        });
    }

    private AgentTool removeBot() {
        Schema schema = Schema.object()
                .str("name", "要移除的假人名")
                .require("name");
        return Tools.of("remove_bot", "移除一个伙伴假人（需要服务器允许）。", schema, (context, arguments) -> {
            if (!plugin.settings().allowCreateBot()) {
                return done(Json.error("服务器没有允许 AI 自己管理假人"));
            }
            String name = Json.getString(arguments, "name", "");
            Bot bot = plugin.bots().byName(name);
            if (bot == null) {
                return done(Json.error("找不到假人 " + name));
            }
            UUID botOwner = plugin.bots().ownerOf(bot);
            if (context.owner() == null || botOwner == null
                    || !botOwner.equals(context.owner().getUniqueId())) {
                return done(Json.error("只能操作你自己的伙伴"));
            }
            boolean removed = plugin.bots().remove(bot, plugin.settings().saveOnRemove());
            if (removed && plugin.botStates() != null) {
                // 和 /buddy bot remove 保持一致：明确移除的假人不要留下存档，否则重启会被恢复回来
                plugin.botStates().delete(botOwner);
            }
            return done(removed ? Json.ok("已移除假人", "bot", name) : Json.error("移除失败"));
        });
    }

    private CompletableFuture<String> done(String value) {
        return CompletableFuture.completedFuture(value);
    }
}
