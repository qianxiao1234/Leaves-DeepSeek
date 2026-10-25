package com.leavesdeepseek.buddy.bot;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 饥饿时自动吃普通食物；血量危急时强制吃最好的食物（不走 AI，不需要同意）。
 */
public final class AutoEater {

    /** 血量急救检查间隔（tick），10 tick = 0.5 秒 */
    private static final long EMERGENCY_PERIOD_TICKS = 10L;
    /** 普通自动进食的冷却（毫秒），避免一段动作刚结束就立刻再来一次 */
    private static final long NORMAL_EAT_COOLDOWN_MS = 4000L;
    /** 强制进食的冷却（毫秒） */
    private static final long EMERGENCY_EAT_COOLDOWN_MS = 2500L;
    /** 饥饿值达到该值时自然回血已经生效，普通食物不再有急救意义 */
    private static final int NATURAL_REGEN_FOOD = 18;

    private final LeavesDeepSeekPlugin plugin;
    private final Set<UUID> eating = ConcurrentHashMap.newKeySet();
    /** 正在「强制进食」（血量危急）的假人：期间不能被「跟着我」打断 */
    private final Set<UUID> forceEating = ConcurrentHashMap.newKeySet();
    /** 下一次允许再次进食的时间戳 */
    private final Map<UUID, Long> nextEatAt = new ConcurrentHashMap<>();

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
        forceEating.clear();
        nextEatAt.clear();
    }

    private boolean onCooldown(Bot bot) {
        Long until = nextEatAt.get(bot.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private void startCooldown(Bot bot, long millis) {
        if (millis > 0) {
            nextEatAt.put(bot.getUniqueId(), System.currentTimeMillis() + millis);
        }
    }

    /** 是否正在强制进食（血量危急时的急救）。 */
    public boolean isForceEating(Bot bot) {
        return bot != null && forceEating.contains(bot.getUniqueId());
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
            if (bot == null || !bot.isValid() || bot.isDead() || bot.isSleeping() || plugin.bots().isDowned(bot)) {
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
            // 已经有一条进食流程（手动 / 急救）在进行，或者刚吃完还在冷却
            if (plugin.botActions().isEating(bot) || onCooldown(bot)) {
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
            startCooldown(bot, NORMAL_EAT_COOLDOWN_MS);
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
            if (bot == null || !bot.isValid() || bot.isDead() || bot.isSleeping() || plugin.bots().isDowned(bot)) {
                continue;
            }
            if (!plugin.profiles().autoEat(player)) {
                continue;
            }
            if (bot.getHealth() > limit + 1.0E-6) {
                continue;
            }
            // 正在躲苦力怕：跑命优先，别停下来吃东西
            if (plugin.combatGuardian() != null && plugin.combatGuardian().isCreeperFleeing(bot)) {
                continue;
            }
            // 手动 / 自动进食正在进行中：不要打断，等它吃完
            if (plugin.botActions().isEating(bot) || onCooldown(bot)) {
                continue;
            }

            UUID botId = bot.getUniqueId();
            if (!eating.add(botId)) {
                continue;
            }
            // 饥饿值满时只有金苹果类还能吃
            boolean fullHunger = bot.getFoodLevel() >= 20;
            Material food = FoodRules.pickEmergency(bot.getInventory(), fullHunger);
            if (food == null) {
                eating.remove(botId);
                startCooldown(bot, EMERGENCY_EAT_COOLDOWN_MS);
                continue;
            }
            if (!FoodRules.isValuable(food) && bot.getFoodLevel() >= NATURAL_REGEN_FOOD) {
                // 饥饿值 18 以上自然回血已经在工作，普通食物吃了也不会更快，
                // 不要再反复刷「切换食物 + 咀嚼」，避免看起来一直在吃却吃不下去。
                eating.remove(botId);
                startCooldown(bot, EMERGENCY_EAT_COOLDOWN_MS);
                if (plugin.settings().debug()) {
                    plugin.getLogger().info("血量急救：" + BotNames.rawName(bot)
                            + " 饥饿值 " + bot.getFoodLevel() + "，等待自然回血");
                }
                continue;
            }
            plugin.log().info("进食", "强制进食 | 假人=" + BotNames.rawName(bot)
                    + " | 食物=" + food.name()
                    + " | 血量=" + (Math.round(bot.getHealth() * 10) / 10.0)
                    + " | 饥饿=" + bot.getFoodLevel());
            startCooldown(bot, EMERGENCY_EAT_COOLDOWN_MS);

            // 强制：打断当前战斗、寻路和 AI 动作，立刻吃。
            // 但正在撤退（被围攻/血低）时**不能打断逃跑** —— 停下来吃药就是站着挨打，
            // 这时只把药塞进嘴里，脚步交给撤退逻辑继续跑。
            boolean retreating = plugin.combatGuardian() != null
                    && plugin.combatGuardian().isRetreating(bot);
            forceEating.add(botId);
            if (!retreating) {
                plugin.botActions().interrupt(bot, "血量过低，先吃东西");
            }
            plugin.botActions().eat(bot, food, true, true).whenComplete((result, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        eating.remove(botId);
                        forceEating.remove(botId);
                        if (bot.isValid() && plugin.botStates() != null) {
                            plugin.botStates().save(bot);
                        }
                    }));
        }
    }
}
