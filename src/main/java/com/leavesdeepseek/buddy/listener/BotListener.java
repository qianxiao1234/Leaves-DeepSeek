package com.leavesdeepseek.buddy.listener;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.bot.BotNames;
import com.leavesdeepseek.buddy.bot.BotService;
import com.leavesdeepseek.buddy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.event.bot.BotDeathEvent;
import org.leavesmc.leaves.event.bot.BotRemoveEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 假人生命周期事件。
 */
public final class BotListener implements Listener {

    private final LeavesDeepSeekPlugin plugin;

    public BotListener(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 假人死亡兜底：让服务端保留背包（不掉落、不掉经验）。
     * <p>Leaves 的假人走的是 {@code BotDeathEvent} 分支，正常不会触发这个事件；
     * 这里只是防止服务端版本差异导致装备掉在地上、而存档里还有一份。</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!(event.getEntity() instanceof Bot bot)) {
            return;
        }
        if (plugin.bodyManager() != null && plugin.bodyManager().isBody(bot)) {
            // 躯壳：即使真的走到了 PlayerDeathEvent（服务端版本差异），也不许掉东西
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            List<ItemStack> drops = event.getDrops();
            if (drops != null) {
                drops.clear();
            }
            return;
        }
        List<ItemStack> drops = event.getDrops();
        if (drops != null) {
            drops.clear();
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    /**
     * 假人死亡：不改掉落物，而是**取消死亡**，进入「倒地」状态并保留全部物品。
     *
     * <p>Leaves 的 {@code ServerBot.die} 会先发 {@code BotDeathEvent}，取消后只会把血量设成 0.1 并返回，
     * 不会调用 {@code removeBot(..., save=false)}（那一步会 {@code dropAll} 把装备丢在地上）。
     * 主人说「复活」或执行 {@code /buddy bot revive} 后恢复正常。</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBotDeath(BotDeathEvent event) {
        Bot bot = event.getBot();
        if (bot == null) {
            return;
        }
        com.leavesdeepseek.agent.AIBody body = plugin.bodyManager() == null
                ? null : plugin.bodyManager().byBot(bot);
        if (body != null) {
            // AI 躯壳：像传统假人一样「拦下死亡」—— 保留全部物品、等 revive，
            // 否则 Leaves 会 removeBot(save=false) → dropAll(true) 把装备撒一地（用户要求）。
            event.setCancelled(true);
            event.setSendDeathMessage(false);
            String rawCause = bot.getLastDamageCause() == null ? "unknown"
                    : bot.getLastDamageCause().getCause().name().toLowerCase(java.util.Locale.ROOT);
            final String cause = rawCause;
            plugin.getServer().getScheduler().runTask(plugin, () -> enterBodyDowned(body, cause));
            return;
        }
        event.setCancelled(true);
        event.setSendDeathMessage(false);
        // 已经处于倒地状态时不要丢掉「倒地前正在跟随」这个标记
        BotService.DownedState previous = plugin.bots().downedState(bot);
        boolean wasFollowing = previous != null
                ? previous.wasFollowing()
                : (plugin.navigator() != null && plugin.navigator().isFollowing(bot));
        plugin.bots().markDowned(bot, wasFollowing);
        // Leaves 在事件取消后会把血量补到 0.1，下一 tick 再接管（加无敌 + 清动作）
        Bukkit.getScheduler().runTask(plugin, () -> enterDowned(bot));
        notifyOwner(bot, "§c你的伙伴 §f" + BotNames.rawName(bot)
                + " §c倒下了……物品一件都没少。说「§f复活§c」或输入 §f/buddy bot revive§c 让他站起来。");
        if (plugin.log() != null) {
            plugin.log().info("伙伴", "假人 " + BotNames.rawName(bot)
                    + " 受致命伤，已拦截死亡并保留全部物品（等待复活）");
        }
    }

    /**
     * 躯壳倒地：清任务、无敌、血量留一点、推 {@code ev.death}，等 {@code body.respawn} / {@code /ai body revive}。
     */
    private void enterBodyDowned(com.leavesdeepseek.agent.AIBody body, String cause) {
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            return;
        }
        body.dead(true);
        body.controller().stopAll("DEATH");
        try {
            bot.setInvulnerable(true);
            bot.setFireTicks(0);
            bot.setFallDistance(0.0f);
            bot.setHealth(Math.max(0.1, Math.min(1.0, bot.getMaxHealth())));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("躯壳倒地处理失败：" + exception.getMessage());
        }
        // 立刻存档：万一随后被外力移除，背包/装备也还在
        if (plugin.bodyManager() != null) {
            plugin.bodyManager().save(body.name());
        }
        body.sensors().event("ev.death", com.leavesdeepseek.agent.JsonRpc.object(
                "cause", cause,
                "pos", com.leavesdeepseek.agent.util.VecUtil.toArray(bot.getLocation()),
                "dropKept", true,
                "downed", true));
        plugin.log().info("躯壳", "躯壳 " + body.name() + " 倒下了（物品已保留），等 revive | cause=" + cause);
    }

    /**
     * 进入倒地状态：无敌、清空动作、暂停跟随；不触碰背包。
     */
    private void enterDowned(Bot bot) {
        if (bot == null || !bot.isValid()) {
            return;
        }
        try {
            if (bot.isSleeping()) {
                bot.wakeup(false);
            }
            bot.setFireTicks(0);
            bot.setFallDistance(0.0f);
            bot.setInvulnerable(true);
            // 服务端取消死亡后会把血量设为 0.1；给一点血便于观察，但仍在倒地状态
            bot.setHealth(Math.min(1.0, Math.max(0.1, bot.getMaxHealth())));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("进入倒地状态失败（" + BotNames.rawName(bot) + "）：" + exception.getMessage());
        }
        if (plugin.navigator() != null) {
            plugin.navigator().stopFollow(bot);
            plugin.navigator().cancel(bot);
        }
        if (plugin.botActions() != null) {
            plugin.botActions().interrupt(bot, "倒下了，等待主人复活");
        }
        if (plugin.botStates() != null) {
            plugin.botStates().save(bot);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBotRemove(BotRemoveEvent event) {
        Bot bot = event.getBot();
        if (bot != null && plugin.bodyManager() != null && plugin.bodyManager().isBody(bot)) {
            // AI 躯壳的存档由 BodyManager 负责，别在这里清背包/删档
            return;
        }
        // 这里绝不删除存档：
        // 服务端 /stop 时 Leaves 会先移除假人再禁用插件，此处的 isShuttingDown() 仍是 false，
        // 若按“是否停机”判断会误删 bot-state.yml，导致下次启动无法恢复。
        // 只有 /buddy bot remove 这类明确指令才会删除存档。
        //
        // 移除前先写存档、再清空背包：Leaves 移除假人时会把装备丢在地上，
        // 掉落物重启后还在，会和存档里的装备重复成两套。
        if (plugin.botStates() != null) {
            plugin.botStates().saveAndClear(bot);
        }
        cleanup(bot, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // 假人也继承 Player，会触发 PlayerJoinEvent；不要为假人创建玩家配置目录
        if (event.getPlayer() instanceof Bot) {
            return;
        }
        // 为每个真实玩家生成个人配置文件
        plugin.profiles().getOrCreate(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.chatListener().clear(event.getPlayer().getUniqueId());
        if (plugin.botStates() != null) {
            for (Bot bot : plugin.bots().botsOf(event.getPlayer())) {
                plugin.botStates().save(bot);
            }
        }
        if (!plugin.settings().removeOnQuit()) {
            return;
        }
        for (Bot bot : new ArrayList<>(plugin.bots().botsOf(event.getPlayer()))) {
            plugin.navigator().cancel(bot);
            // 保存了存档再移除，先把背包清空，避免掉落装备和存档重复
            if (plugin.settings().saveOnRemove() && plugin.botStates() != null) {
                plugin.botStates().saveAndClear(bot);
            }
            plugin.bots().remove(bot, plugin.settings().saveOnRemove());
        }
    }

    private void cleanup(Bot bot, boolean deleteState) {
        UUID ownerId = plugin.bots().ownerOf(bot);
        plugin.navigator().cancel(bot);
        plugin.agent().clear(bot.getUniqueId());
        plugin.bots().unbind(bot);
        if (deleteState && ownerId != null && !plugin.isShuttingDown() && plugin.botStates() != null) {
            plugin.botStates().delete(ownerId);
        }
    }

    private void notifyOwner(Bot bot, String message) {
        UUID ownerId = plugin.bots().ownerOf(bot);
        if (ownerId == null) {
            return;
        }
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null && owner.isOnline()) {
            Text.send(owner, message);
        }
    }
}
