package com.leavesdeepseek.agent.task;

import com.leavesdeepseek.agent.AIBody;
import com.leavesdeepseek.agent.JsonRpc;
import com.leavesdeepseek.agent.util.VecUtil;
import com.leavesdeepseek.buddy.bot.WeaponRules;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.entity.bot.action.AttackAction;

/**
 * {@code act.attack}：内环控制出刀节拍（等武器冷却），避免 0 伤害连点。
 */
public final class AttackTask extends AbstractTask {

    private static final double REACH = 3.0;

    private final Integer entityId;
    private final String kind;
    private final int times;
    private final boolean chase;

    private LivingEntity target;
    private int hits;
    private int cooldown;
    private int idleTicks;

    public AttackTask(Integer entityId, String kind, int times, boolean chase) {
        this.entityId = entityId;
        this.kind = kind == null ? "hostile" : kind;
        this.times = Math.max(1, Math.min(64, times));
        this.chase = chase;
        this.deadline = startedAt + 45_000L;
    }

    @Override
    public String type() {
        return "attack";
    }

    @Override
    public void start(AIBody body) {
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳不在线");
            return;
        }
        target = resolve(body, bot);
        if (target == null) {
            fail(JsonRpc.NOT_FOUND, "附近没有可攻击的目标");
            return;
        }
        if (target instanceof Player && !body.configBoolean("allow-pvp", body.manager().config().allowPvp())) {
            fail(JsonRpc.DENIED, "服务器禁止躯壳 PVP");
        }
    }

    private LivingEntity resolve(AIBody body, Bot bot) {
        if (entityId != null) {
            Entity entity = VecUtil.entityById(bot, entityId);
            return entity instanceof LivingEntity living && living.isValid() ? living : null;
        }
        double radius = 16.0;
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : bot.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                continue;
            }
            if (living instanceof Bot) {
                continue;
            }
            boolean hostile = living instanceof Monster;
            if ("hostile".equals(kind) && !hostile) {
                continue;
            }
            if ("passive".equals(kind) && hostile) {
                continue;
            }
            if (living instanceof Player && !body.configBoolean("allow-pvp", body.manager().config().allowPvp())) {
                continue;
            }
            double distance = living.getLocation().distanceSquared(bot.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    @Override
    public void tick(AIBody body) {
        if (done() || failed()) {
            return;
        }
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳已离线");
            return;
        }
        if (timeout()) {
            fail(JsonRpc.TIMEOUT, "攻击超时（已命中 " + hits + " 次）");
            return;
        }
        if (target == null || !target.isValid() || target.isDead()) {
            if (hits >= times || target == null) {
                finish();
            } else {
                finish();
            }
            return;
        }
        if (cooldown > 0) {
            cooldown--;
        }
        Location targetLocation = target.getLocation();
        if (bot.getWorld() == null || !bot.getWorld().equals(targetLocation.getWorld())) {
            fail(JsonRpc.NOT_FOUND, "目标不在同一世界");
            return;
        }

        Vector toTarget = targetLocation.clone().add(0, target.getHeight() * 0.5, 0)
                .toVector().subtract(bot.getEyeLocation().toVector());
        double distance = toTarget.length();
        if (distance > REACH) {
            if (!chase) {
                idleTicks++;
                if (idleTicks > 40) {
                    fail(JsonRpc.BLOCKED, "目标在 " + Math.round(distance * 10) / 10.0 + " 格外且未开启追击");
                }
                return;
            }
            idleTicks = 0;
            if (!body.manager().plugin().navigator().isNavigating(bot)) {
                body.manager().plugin().navigator().navigate(bot, targetLocation, 2.0, 60);
            }
            return;
        }

        body.manager().plugin().navigator().cancel(bot);
        Location aim = new Location(bot.getWorld(), 0, 0, 0).setDirection(toTarget);
        bot.setRotation(aim.getYaw(), aim.getPitch());
        if (cooldown > 0) {
            return;
        }
        body.manager().plugin().bots().stopActions(bot, AttackAction.class);
        AttackAction action = AttackAction.create();
        action.setStartDelayTick(1);
        action.setDoIntervalTick(1);
        action.setDoNumber(1);
        bot.addAction(action);
        hits++;
        cooldown = Math.max(4, WeaponRules.cooldownTicks(bot.getInventory().getItemInMainHand()));
        if (hits >= times) {
            finish();
        }
    }

    @Override
    public Location target() {
        return target == null ? null : target.getLocation();
    }

    @Override
    public double progress() {
        return Math.min(1.0, (double) hits / (double) times);
    }

    @Override
    public void cancel() {
        fail(JsonRpc.TIMEOUT, "CANCELLED");
    }
}
