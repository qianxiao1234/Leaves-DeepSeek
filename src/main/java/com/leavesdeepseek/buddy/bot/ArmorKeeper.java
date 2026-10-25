package com.leavesdeepseek.buddy.bot;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动补空盔甲槽。
 *
 * <p>只处理“空槽位”，绝不自动升级已有装备——升级必须由主人明确要求（AI 调用 equip_armor）。</p>
 */
public final class ArmorKeeper {

    private static final long CHECK_INTERVAL_TICKS = 100L;

    private final LeavesDeepSeekPlugin plugin;
    private BukkitTask task;

    public ArmorKeeper(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
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
            plugin.log().error("盔甲", "自动补装备检查出错：" + throwable.getMessage(), throwable);
        }
    }

    private void checkAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player instanceof Bot) {
                continue;
            }
            Bot bot = plugin.bots().primaryBot(player);
            if (bot == null || !bot.isValid() || bot.isDead() || bot.isSleeping() || plugin.bots().isDowned(bot)) {
                continue;
            }
            // AI 正在处理请求时不要动背包，避免和 give_item / 合成互相踩
            if (plugin.agent().isBusy(bot)) {
                continue;
            }
            List<Integer> empty = emptySlots(bot);
            if (empty.isEmpty()) {
                continue;
            }
            ArmorRules.Report report = ArmorRules.equip(bot, empty);
            if (report.changed() && plugin.botStates() != null) {
                plugin.botStates().save(bot);
                if (plugin.settings().chatLogToConsole()) {
                    plugin.getLogger().info("[盔甲] " + BotNames.rawName(bot) + " 自动穿上 "
                            + report.changes().size() + " 件");
                }
            }
        }
    }

    private List<Integer> emptySlots(Bot bot) {
        List<Integer> slots = new ArrayList<>();
        for (int slot : ArmorRules.ALL_SLOTS) {
            ItemStack current = bot.getInventory().getItem(slot);
            if (current == null || current.getType().isAir()) {
                slots.add(slot);
            }
        }
        return slots;
    }
}
