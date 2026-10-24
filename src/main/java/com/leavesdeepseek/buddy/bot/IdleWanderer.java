package com.leavesdeepseek.buddy.bot;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.config.PluginSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.Random;

/**
 * 空闲随机走动：低频、小范围，且不会打断跟随或正在执行的任务。
 */
public final class IdleWanderer {

    private final LeavesDeepSeekPlugin plugin;
    private final Random random = new Random();

    private BukkitTask task;

    public IdleWanderer(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long period = Math.max(20L, plugin.settings().idleCheckIntervalSeconds() * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        try {
            checkAll();
        } catch (Throwable throwable) {
            // 定时任务里抛异常会被 Bukkit 永久取消，这里必须兜住
            plugin.log().error("闲逛", "空闲走动检查出错：" + throwable.getMessage(), throwable);
        }
    }

    private void checkAll() {
        PluginSettings settings = plugin.settings();
        if (!settings.idleWanderEnabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            Bot bot = plugin.bots().primaryBot(player);
            if (bot == null || !bot.isValid()) {
                continue;
            }
            if (bot.getWorld() == null || player.getWorld() == null
                    || !bot.getWorld().equals(player.getWorld())) {
                continue;
            }
            if (bot.isSleeping() || bot.isDead() || bot.isInsideVehicle()) {
                continue;
            }
            if (!plugin.profiles().idleWander(player)) {
                continue;
            }
            if (plugin.navigator().isWandering(bot) || plugin.navigator().isBusy(bot)) {
                continue;
            }
            // 正在挖掘/攻击/钓鱼等动作时不走动
            if (!plugin.bots().actionNames(bot).isEmpty()) {
                continue;
            }
            // 正在战斗或躲避强敌时不要闲逛
            if (plugin.combatGuardian() != null && plugin.combatGuardian().isEngaged(bot)) {
                continue;
            }
            // 主人走远了就先跟上，而不是闲逛
            if (bot.getLocation().distanceSquared(player.getLocation()) > 16 * 16) {
                continue;
            }
            if (random.nextDouble() >= settings.idleWanderChance()) {
                continue;
            }

            Location target = randomTarget(bot, settings.idleWanderRadius());
            if (target == null) {
                continue;
            }
            plugin.navigator().wander(bot, target, settings.idleWanderTimeoutSeconds() * 20);
        }
    }

    private Location randomTarget(Bot bot, double radius) {
        Location origin = bot.getLocation();
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }
        double maxDistance = Math.max(1.0, radius);
        for (int attempt = 0; attempt < 4; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = 1.0 + random.nextDouble() * Math.max(0.1, maxDistance - 1.0);
            int x = origin.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = origin.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            Location target = new Location(world, x + 0.5, Math.floor(origin.getY()), z + 0.5);

            if (!target.getBlock().isPassable() || !target.clone().add(0, 1, 0).getBlock().isPassable()) {
                continue;
            }
            return target;
        }
        return null;
    }
}
