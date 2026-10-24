package com.leavesdeepseek.buddy.listener;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.bot.BotNames;
import com.leavesdeepseek.buddy.bot.BotStateService;
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
     * 假人死亡：把掉落物收回来，重生后放回背包。
     * <p>服务端默认会把装备丢在地上，而这些掉落物会跟着区块一起存盘，重启后仍然存在；
     * 插件又会把假人的背包恢复回来，于是凭空多出一整套装备。
     * 这里在掉落物真正生成之前清空 {@code getDrops()}，并记下快照。</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!(event.getEntity() instanceof Bot bot)) {
            return;
        }
        if (plugin.botStates() == null || !plugin.botStates().enabled()) {
            return;
        }
        List<ItemStack> drops = event.getDrops();
        if (drops == null || drops.isEmpty()) {
            // keepInventory 之类的情况本来就不掉东西，不要动背包
            return;
        }
        ItemStack[] snapshot = BotStateService.inventoryFromDrops(drops);
        int count = drops.size();
        drops.clear();
        event.setDroppedExp(0);
        event.setKeepLevel(true);
        plugin.botStates().rememberDeathInventory(bot, snapshot);
        if (plugin.log() != null) {
            plugin.log().info("伙伴", "假人 " + BotNames.rawName(bot) + " 死亡，已收回 " + count
                    + " 件物品（不掉落，重生后放回背包）");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBotDeath(BotDeathEvent event) {
        Bot bot = event.getBot();
        notifyOwner(bot, "§c你的伙伴 §f" + BotNames.rawName(bot) + " §c倒下了……");
        // 保留存档，避免假人阵亡后重启服务器就再也回不来。
        // save() 内部会优先使用死亡快照，不会把空背包写进存档。
        if (plugin.botStates() != null) {
            plugin.botStates().save(bot);
        }
        cleanup(bot, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBotRemove(BotRemoveEvent event) {
        Bot bot = event.getBot();
        // 这里绝不删除存档：
        // 服务端 /stop 时 Leaves 会先移除假人再禁用插件，此处的 isShuttingDown() 仍是 false，
        // 若按“是否停机”判断会误删 bot-state.yml，导致下次启动无法恢复。
        // 只有 /buddy remove 这类明确指令才会删除存档。
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
