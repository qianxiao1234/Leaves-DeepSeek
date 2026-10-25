package com.leavesdeepseek.agent;

import com.google.gson.JsonObject;
import com.leavesdeepseek.agent.util.VecUtil;
import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.leavesmc.leaves.entity.bot.Bot;
/**
 * 躯壳相关事件 -> {@code ev.*} 推送（规格 §7.4）。
 *
 * <p>只处理 {@link AIBody} 注册表里的实体，传统假人完全不受影响。</p>
 */
public final class AgentEvents implements Listener {

    private final LeavesDeepSeekPlugin plugin;

    public AgentEvents(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    private BodyManager manager() {
        return plugin.bodyManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        AIBody body = bodyOf(event.getEntity());
        if (body == null) {
            return;
        }
        String cause = event.getCause().name().toLowerCase(java.util.Locale.ROOT);
        String source = cause;
        String sourceName = "";
        int attackerId = -1;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof LivingEntity living) {
                source = living.getType().name().toLowerCase(java.util.Locale.ROOT);
                sourceName = living instanceof org.bukkit.entity.Player player ? player.getName() : "";
                attackerId = living.getEntityId();
            }
        }
        double hpAfter = Math.max(0.0, ((LivingEntity) event.getEntity()).getHealth() - event.getFinalDamage());
        // 字段统一（验收报告 P0-5）：amount / cause / source / sourceName / hpAfter / pos / attackerId
        JsonObject params = JsonRpc.object("amount", VecUtil.round(event.getFinalDamage()),
                "cause", cause,
                "source", source,
                "sourceName", sourceName,
                "hpAfter", VecUtil.round(hpAfter),
                "pos", VecUtil.toArray(event.getEntity().getLocation()));
        if (attackerId > 0) {
            params.addProperty("attackerId", attackerId);
        }
        body.sensors().event("ev.damage", params);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        AIBody body = bodyOf(event.getEntity());
        if (body == null) {
            return;
        }
        // 躯壳一律保物品（与 BotListener 里取消 BotDeathEvent 的策略一致）
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        body.dead(true);
        body.controller().stopAll("DEATH");
        String cause = "unknown";
        EntityDamageEvent last = event.getEntity().getLastDamageCause();
        if (last != null) {
            cause = last.getCause().name().toLowerCase(java.util.Locale.ROOT);
        }
        Location deathPos = event.getEntity().getLocation().clone();
        body.sensors().event("ev.death", JsonRpc.object("cause", cause,
                "pos", VecUtil.toArray(deathPos),
                "dropKept", true,
                "downed", true));
        manager().auditEvent(body.name(), "ev.death", JsonRpc.object("cause", cause));
        manager().announceDeath(body, cause);
        // 死亡瞬间先存档：背包/装备/经验都还在，重启也能恢复
        manager().save(body.name());

        // Leaves 死亡后会移除假人实体：这里等一 tick 再看，必要时把躯壳标成 offline
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            AIBody current = manager().byName(body.name());
            if (current == null) {
                return;
            }
            if (current.bot() == null || !current.bot().isValid()) {
                current.bot(null);
                current.state(AIBody.State.OFFLINE);
                current.sensors().stateEvent(AIBody.State.OFFLINE, "death");
            }
        }, 2L);

        if (manager().config().autoRespawn()) {
            int delay = Math.max(1, manager().config().respawnDelaySeconds()) * 20;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                AIBody current = manager().byName(body.name());
                if (current == null) {
                    return;
                }
                try {
                    manager().revive(current);
                } catch (Throwable throwable) {
                    plugin.log().warn("躯壳", "自动重生失败：" + throwable.getMessage());
                }
            }, delay);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        AIBody body = bodyOf(event.getEntity());
        if (body == null) {
            return;
        }
        Item item = event.getItem();
        body.sensors().event("ev.itemPickup", JsonRpc.object(
                "item", item.getItemStack().getType().name().toLowerCase(java.util.Locale.ROOT),
                "count", item.getItemStack().getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (manager() == null) {
            return;
        }
        String sender = event.getPlayer().getName();
        String text = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (AIBody body : manager().bodies()) {
                Location location = body.location();
                if (location == null || location.getWorld() == null
                        || !location.getWorld().equals(event.getPlayer().getWorld())) {
                    continue;
                }
                if (location.distance(event.getPlayer().getLocation()) > manager().config().chatRadius()) {
                    continue;
                }
                body.sensors().event("ev.chat", JsonRpc.object("sender", sender, "text", text, "type", "public"));
            }
        });
    }

    private AIBody bodyOf(Entity entity) {
        if (!(entity instanceof Bot bot)) {
            return null;
        }
        if (manager() == null) {
            return null;
        }
        return manager().byBot(bot);
    }
}
