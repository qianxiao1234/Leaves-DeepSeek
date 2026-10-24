package com.leavesdeepseek.buddy.bot;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 饥饿时自动吃普通食物（不会自动吃贵重食物）。
 */
public final class AutoEater {

    private final LeavesDeepSeekPlugin plugin;
    private final Set<UUID> eating = ConcurrentHashMap.newKeySet();

    private BukkitTask task;

    public AutoEater(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long period = Math.max(20L, plugin.settings().eatCheckIntervalSeconds() * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        eating.clear();
    }

    private void tick() {
        try {
            checkAll();
        } catch (Throwable throwable) {
            // 定时任务里抛异常会被 Bukkit 永久取消，这里必须兜住
            plugin.log().error("进食", "自动进食检查出错：" + throwable.getMessage(), throwable);
        }
    }

    private void checkAll() {
        if (!plugin.settings().autoEat()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            Bot bot = plugin.bots().primaryBot(player);
            if (bot == null || !bot.isValid() || bot.isDead() || bot.isSleeping()) {
                continue;
            }
            if (!plugin.profiles().autoEat(player)) {
                continue;
            }
            if (plugin.agent().isBusy(bot) || plugin.navigator().isBusy(bot)) {
                continue;
            }
            // 战斗中的进食由战斗逻辑自己处理，避免和攻击动作冲突
            if (plugin.combatGuardian() != null && plugin.combatGuardian().isEngaged(bot)) {
                continue;
            }
            if (!plugin.bots().actionNames(bot).isEmpty()) {
                continue;
            }
            if (bot.getFoodLevel() > plugin.settings().eatThreshold()) {
                continue;
            }

            UUID botId = bot.getUniqueId();
            if (!eating.add(botId)) {
                continue;
            }
            Material food = FoodRules.pickBest(bot.getInventory(), false);
            if (food == null) {
                eating.remove(botId);
                continue;
            }
            plugin.botActions().eat(bot, food, false).whenComplete((result, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        eating.remove(botId);
                        if (bot.isValid() && plugin.botStates() != null) {
                            plugin.botStates().save(bot);
                        }
                    }));
        }
    }
}
