package com.leavesdeepseek.agent.task;

import com.leavesdeepseek.agent.AIBody;
import com.leavesdeepseek.agent.JsonRpc;
import com.leavesdeepseek.agent.util.VecUtil;
import com.leavesdeepseek.buddy.bot.WeaponRules;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.entity.bot.action.AttackAction;
import org.leavesmc.leaves.entity.bot.action.UseItemOnAction;

/** {@code act.attack}: count server-confirmed damage and avoid blind swings. */
public final class AttackTask extends AbstractTask {

    private static final double REACH = 3.0;
    private static final double USE_REACH = 4.5;
    private static final int MAX_MISSED_SWINGS = 3;
    private static final int MAX_BLOCKED_TICKS = 200;
    private static final int SWING_CONFIRM_TICKS = 4;

    private final Integer entityId;
    private final String kind;
    private final int times;
    private final boolean chase;

    private AIBody owner;
    private Bot bot;
    private LivingEntity target;
    private int hits;
    private int cooldown;
    private int navigationRetryTicks;
    private int missedSwings;
    private int pendingSwingTicks;
    private int blockedTicks;
    private int idleTicks;
    private int doorAttempts;
    private int doorWaitTicks;
    private boolean pendingSwing;
    private boolean waitingForDoor;
    private Block doorToOpen;

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
        owner = body;
        bot = body.bot();
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
            return;
        }
        body.manager().plugin().navigator().cancel(bot);
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
        if (bot == null || !bot.isValid()) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳已离线");
            return;
        }
        if (timeout()) {
            stopNavigation();
            stopAttackActions();
            fail(JsonRpc.TIMEOUT, "攻击超时（确认命中 " + hits + " 次）");
            return;
        }
        if (target == null || !target.isValid()) {
            if ((target != null && target.isDead()) || hits > 0) {
                stopNavigation();
                stopAttackActions();
                finish();
            } else {
                stopNavigation();
                stopAttackActions();
                fail(JsonRpc.NOT_FOUND, "攻击目标已消失");
            }
            return;
        }
        if (target.isDead()) {
            stopNavigation();
            stopAttackActions();
            finish();
            return;
        }
        if (bot.getWorld() == null || !bot.getWorld().equals(target.getWorld())) {
            stopNavigation();
            stopAttackActions();
            fail(JsonRpc.NOT_FOUND, "目标不在同一世界");
            return;
        }
        if (hits >= times) {
            stopNavigation();
            stopAttackActions();
            finish();
            return;
        }

        if (waitingForDoor) {
            if (--doorWaitTicks <= 0) {
                body.manager().plugin().bots().stopActions(bot, UseItemOnAction.class);
                if (doorToOpen != null && doorToOpen.getBlockData() instanceof Openable openable
                        && openable.isOpen()) {
                    doorToOpen = null;
                    doorAttempts = 0;
                }
                waitingForDoor = false;
            }
            return;
        }
        if (navigationRetryTicks > 0) {
            navigationRetryTicks--;
        }

        if (pendingSwing && ++pendingSwingTicks >= SWING_CONFIRM_TICKS) {
            pendingSwing = false;
            missedSwings++;
            if (missedSwings >= MAX_MISSED_SWINGS) {
                stopNavigation();
                stopAttackActions();
                fail(JsonRpc.BLOCKED, "连续挥击未造成伤害；请检查视线、距离或目标无敌状态");
                return;
            }
        }
        if (cooldown > 0) {
            cooldown--;
        }

        Location targetLocation = target.getLocation();
        Vector toTarget = targetLocation.clone().add(0, target.getHeight() * 0.5, 0)
                .toVector().subtract(bot.getEyeLocation().toVector());
        double distance = toTarget.length();
        if (!bot.hasLineOfSight(target)) {
            blockedTicks++;
            Block obstruction = firstObstruction(toTarget);
            if (obstruction != null && beginDoorOpen(body, obstruction)) {
                return;
            }
            if (chase && navigationRetryTicks == 0
                    && !body.manager().plugin().navigator().isNavigating(bot)) {
                Location navTarget = obstruction == null ? targetLocation : obstruction.getLocation();
                body.manager().plugin().navigator().navigate(bot, navTarget, 2.0, 100);
                navigationRetryTicks = 20;
            }
            if ((!chase && blockedTicks >= 40) || blockedTicks >= MAX_BLOCKED_TICKS) {
                stopNavigation();
                stopAttackActions();
                fail(JsonRpc.NO_PATH, "到目标的视线被方块遮挡，无法接近");
            }
            return;
        }
        blockedTicks = 0;

        if (distance > REACH) {
            if (!chase) {
                if (++idleTicks >= 40) {
                    fail(JsonRpc.BLOCKED,
                            "目标在 " + Math.round(distance * 10) / 10.0 + " 格外且未开启追击");
                }
                return;
            }
            idleTicks = 0;
            if (navigationRetryTicks == 0 && !body.manager().plugin().navigator().isNavigating(bot)) {
                body.manager().plugin().navigator().navigate(bot, targetLocation, 2.0, 100);
                navigationRetryTicks = 20;
            }
            return;
        }
        idleTicks = 0;

        if (body.manager().plugin().navigator().isNavigating(bot)) {
            body.manager().plugin().navigator().cancel(bot);
        }
        Location aim = new Location(bot.getWorld(), 0, 0, 0).setDirection(toTarget);
        bot.setRotation(aim.getYaw(), aim.getPitch());
        if (cooldown > 0 || pendingSwing) {
            return;
        }
        body.manager().plugin().bots().stopActions(bot, AttackAction.class);
        AttackAction action = AttackAction.create();
        action.setStartDelayTick(1);
        action.setDoIntervalTick(1);
        action.setDoNumber(1);
        pendingSwing = true;
        pendingSwingTicks = 0;
        bot.addAction(action);
        cooldown = Math.max(4, WeaponRules.cooldownTicks(bot.getInventory().getItemInMainHand()));
    }

    private Block firstObstruction(Vector toTarget) {
        double length = toTarget.length();
        if (length <= 0.0) {
            return null;
        }
        RayTraceResult trace = bot.getWorld().rayTraceBlocks(bot.getEyeLocation(),
                toTarget.clone().multiply(1.0 / length), length, FluidCollisionMode.NEVER, true);
        return trace == null ? null : trace.getHitBlock();
    }

    private boolean beginDoorOpen(AIBody body, Block obstruction) {
        if (!(obstruction.getBlockData() instanceof Openable openable) || openable.isOpen()
                || !isManuallyOpenable(obstruction.getType())) {
            return false;
        }
        if (!obstruction.equals(doorToOpen)) {
            doorToOpen = obstruction;
            doorAttempts = 0;
        }
        if (doorAttempts >= 2) {
            return false;
        }
        BoundingBox bounds = obstruction.getBoundingBox();
        Location hit = new Location(bot.getWorld(),
                (bounds.getMinX() + bounds.getMaxX()) * 0.5,
                (bounds.getMinY() + bounds.getMaxY()) * 0.5,
                (bounds.getMinZ() + bounds.getMaxZ()) * 0.5);
        if (bot.getEyeLocation().distance(hit) > USE_REACH) {
            return false;
        }
        body.manager().plugin().navigator().cancel(bot);
        Location aim = new Location(bot.getWorld(), 0, 0, 0)
                .setDirection(hit.toVector().subtract(bot.getEyeLocation().toVector()));
        bot.setRotation(aim.getYaw(), aim.getPitch());
        body.manager().plugin().bots().stopActions(bot, UseItemOnAction.class);
        UseItemOnAction action = UseItemOnAction.create();
        action.setStartDelayTick(1);
        action.setDoIntervalTick(1);
        action.setDoNumber(1);
        action.setUseTickTimeout(20);
        bot.addAction(action);
        doorAttempts++;
        doorWaitTicks = 4;
        waitingForDoor = true;
        return true;
    }

    private static boolean isManuallyOpenable(Material material) {
        String name = material.name();
        return (name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE_GATE"))
                && !name.startsWith("IRON_");
    }

    /** Called by the damage event listener only for uncancelled damage caused by this bot. */
    public void confirmDamage(Entity victim, double damage) {
        if (done() || failed() || !pendingSwing || target == null || damage <= 0.0
                || !target.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        hits = Math.min(times, hits + 1);
        missedSwings = 0;
        pendingSwing = false;
        if (hits >= times) {
            finish();
        }
    }

    private void stopNavigation() {
        if (owner != null && bot != null && bot.isValid()) {
            owner.manager().plugin().navigator().cancel(bot);
        }
    }

    private void stopAttackActions() {
        if (owner != null && bot != null && bot.isValid()) {
            owner.manager().plugin().bots().stopActions(bot, AttackAction.class);
            owner.manager().plugin().bots().stopActions(bot, UseItemOnAction.class);
        }
    }

    @Override
    public Location target() {
        return target == null ? null : target.getLocation();
    }

    @Override
    public double progress() {
        if (done()) {
            return 1.0;
        }
        return Math.min(1.0, (double) hits / (double) times);
    }

    @Override
    public void cancel() {
        stopNavigation();
        stopAttackActions();
        fail(JsonRpc.TIMEOUT, "CANCELLED");
    }
}
