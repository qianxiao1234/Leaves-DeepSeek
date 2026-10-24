package com.leavesdeepseek.buddy.tools;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.ai.AgentTool;
import com.leavesdeepseek.buddy.ai.Schema;
import com.leavesdeepseek.buddy.ai.Tools;
import com.leavesdeepseek.buddy.bot.ThreatRules;
import com.leavesdeepseek.buddy.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 战斗相关工具。
 */
public final class CombatTools {

    private final LeavesDeepSeekPlugin plugin;

    public CombatTools(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public List<AgentTool> all() {
        return List.of(attackNearest(), attackPlayer());
    }

    private AgentTool attackNearest() {
        Schema schema = Schema.object()
                .str("entity_type", "可选的实体类型名，例如 ZOMBIE、SKELETON；不填则攻击最近的敌对生物")
                .integer("radius", "搜索半径，默认 8，最大 16");
        return Tools.of("attack_nearest", "走近并攻击附近最近的生物（不会主动攻击玩家）。", schema, (context, arguments) -> {
            Bot bot = context.bot();
            int radius = Math.max(1, Math.min(16, Json.getInt(arguments, "radius", 8)));
            String typeName = Json.getString(arguments, "entity_type", "");

            List<LivingEntity> candidates = new ArrayList<>();
            for (Entity entity : bot.getNearbyEntities(radius, radius, radius)) {
                if (!(entity instanceof LivingEntity living) || living instanceof Player || living instanceof ArmorStand) {
                    continue;
                }
                if (!living.isValid() || living.isDead()) {
                    continue;
                }
                if (ThreatRules.isPassive(living.getType())) {
                    continue;
                }
                if (!typeName.isBlank() && !living.getType().name().equalsIgnoreCase(typeName.trim())) {
                    continue;
                }
                candidates.add(living);
            }
            if (candidates.isEmpty()) {
                return done(Json.error("附近没有可以攻击的目标"));
            }
            candidates.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(bot.getLocation())));
            LivingEntity target = candidates.get(0);
            return plugin.botActions().attack(bot, target);
        });
    }

    private AgentTool attackPlayer() {
        Schema schema = Schema.object()
                .str("player", "要攻击的玩家名")
                .require("player");
        return Tools.of("attack_player", "攻击指定玩家。仅在主人明确要求时使用，不能攻击主人自己。", schema,
                (context, arguments) -> {
                    String name = Json.getString(arguments, "player", "").trim();
                    Player target = Bukkit.getPlayerExact(name);
                    if (target == null) {
                        return done(Json.error("找不到在线玩家 " + name));
                    }
                    if (context.owner() != null && target.getUniqueId().equals(context.owner().getUniqueId())) {
                        return done(Json.error("我不能攻击主人"));
                    }
                    if (!plugin.settings().allowAttack()) {
                        return done(Json.error("服务器配置禁止假人攻击"));
                    }
                    return plugin.botActions().attack(context.bot(), target);
                });
    }

    private CompletableFuture<String> done(String value) {
        return CompletableFuture.completedFuture(value);
    }
}
