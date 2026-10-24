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
 * 饥饿时自动吃普通食物；血量危急时强制吃最好的食物（不走 AI，不需要同意）。
 */
public final class AutoEater {

    /** 血量急救检查间隔（tick），10 tick = 0.5 秒 */
    private static final long EMERGENCY_PERIOD_TICKS = 10L;

    private final LeavesDeepSeekPlugin plugin;
    private final Set<UUID> eating = ConcurrentHashMap.newKeySet();

    private BukkitTask task;
    private BukkitTask emergencyTask;

    public AutoEater(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long period = Math.max(20L, plugin.settings().eatCheckIntervalSeconds() * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
        emergencyTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickEmergency,
                EMERGENCY_PERIOD_TICKS, EMERGENCY_PERIOD_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (emergencyTask != null) {
            emergencyTask.cancel();
            emergencyTask = null;
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

    private void tickEmergency() {
        try {
            checkEmergency();
        } catch (Throwable throwable) {
            plugin.log().error("进食", "紧急进食检查出错：" + throwable.getMessage(), throwable);
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

    /**
     * 血量低于或等于配置值时强制进食：直接打断战斗/寻路/AI 动作，
     * 优先吃高饱食度食物（附魔金苹果最优先），不需要主人同意，也不经过 AI 决策。
     */
    private void checkEmergency() {
        if (!plugin.settings().autoEat()) {
            return;
        }
        int limit = plugin.settings().emergencyEatHealth();
        if (limit <= 0) {
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
            if (bot.getHealth() > limit + 1.0E-6) {
                continue;
            }

            UUID botId = bot.getUniqueId();
            if (!eating.add(botId)) {
                continue;
            }
            // 饥饿值满时只有金苹果类还能吃
            Material food = FoodRules.pickEmergency(bot.getInventory(), bot.getFoodLevel() >= 20);
            if (food == null) {
                eating.remove(botId);
                continue;
            }
            if (plugin.settings().debug()) {
                plugin.getLogger().info("血量急救：" + BotNames.rawName(bot) + " 血量 "
                        + (Math.round(bot.getHealth() * 10) / 10.0) + "，强制进食 " + food.name());
            }

            // 强制：打断当前战斗、寻路和 AI 动作，立刻吃
            plugin.botActions().interrupt(bot, "血量过低，先吃东西");
            plugin.botActions().eat(bot, food, true).whenComplete((result, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        eating.remove(botId);
                        if (bot.isValid() && plugin.botStates() != null) {
                            plugin.botStates().save(bot);
                        }
                    }));
        }
    }
}
