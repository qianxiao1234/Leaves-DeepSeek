package com.leavesdeepseek.buddy.bot;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动战斗：反击附近敌对生物，并在主人被攻击时主动护主。
 */
public final class CombatGuardian implements Listener {

    private final LeavesDeepSeekPlugin plugin;
    private final Set<UUID> attacking = ConcurrentHashMap.newKeySet();
    private final Set<UUID> fleeing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, DefenseCall> calls = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private BukkitTask task;

    public CombatGuardian(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long period = Math.max(10, plugin.settings().combatCheckIntervalTicks());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        attacking.clear();
        fleeing.clear();
        calls.clear();
        cooldowns.clear();
    }

    /**
     * 假人是否正在战斗或逃跑中（供空闲走动等避免打断）。
     */
    public boolean isEngaged(Bot bot) {
        if (bot == null) {
            return false;
        }
        UUID botId = bot.getUniqueId();
        return attacking.contains(botId) || fleeing.contains(botId);
    }

    /**
     * 主人被敌对生物攻击：记录目标，下一次检测时护主。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player owner)) {
            return;
        }
        if (!plugin.settings().defendOwner() || !plugin.profiles().defendOwner(owner)) {
            return;
        }
        Entity damager = resolveDamager(event.getDamager());
        if (!isHostile(damager)) {
            return;
        }
        Bot bot = plugin.bots().primaryBot(owner);
        if (bot == null || !bot.isValid()) {
            return;
        }
        if (bot.getWorld() == null || !bot.getWorld().equals(owner.getWorld())) {
            return;
        }
        double radius = plugin.settings().defendOwnerRadius();
        if (bot.getLocation().distanceSquared(owner.getLocation()) > radius * radius) {
            return;
        }

        calls.put(owner.getUniqueId(),
                new DefenseCall(damager.getUniqueId(), System.currentTimeMillis() + 12000L));
        Bukkit.getScheduler().runTask(plugin, () -> handleOwner(owner));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        calls.remove(event.getPlayer().getUniqueId());
    }
    private void tick() {
        long now = System.currentTimeMillis();
        calls.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        for (Player player : Bukkit.getOnlinePlayers()) {
            handleOwner(player);
        }
    }

    private void handleOwner(Player owner) {
        if (!owner.isOnline()) {
            return;
        }
        Bot bot = plugin.bots().primaryBot(owner);
        if (bot == null || !bot.isValid() || bot.isDead() || bot.isSleeping()) {
            return;
        }
        UUID botId = bot.getUniqueId();
        if (attacking.contains(botId) || fleeing.contains(botId)) {
            return;
        }
        Long cooldownUntil = cooldowns.get(botId);
        if (cooldownUntil != null && cooldownUntil > System.currentTimeMillis()) {
            return;
        }
        // 不打断 AI 任务、寻路或正在进行的动作（单纯跟随/移动可以被打断）
        if (plugin.agent().isBusy(bot) || plugin.navigator().isBusy(bot)) {
            return;
        }
        if (plugin.bots().hasBlockingAction(bot)) {
            return;
        }
        if (bot.getWorld() == null || !bot.getWorld().equals(owner.getWorld())) {
            return;
        }

        // 坚守者：不交战，潜行离开（脚步声会吸引它）
        LivingEntity warden = findNearestWarden(bot, Math.max(16.0, plugin.settings().avoidRadius()));
        if (warden != null) {
            sneakAway(owner, bot, warden);
            return;
        }

        // 被 4 只以上围攻：撤退
        if (countAttackers(bot, ThreatRules.GROUP_RADIUS) >= ThreatRules.GROUP_RETREAT_COUNT) {
            groupRetreat(owner, bot);
            return;
        }

        // 配置里额外不交战的生物
        LivingEntity avoided = findNearestAvoided(bot, plugin.settings().avoidRadius());
        if (avoided != null) {
            flee(owner, bot, avoided);
            return;
        }

        DefenseCall call = calls.get(owner.getUniqueId());
        if (call != null) {
            if (call.expiresAt() < System.currentTimeMillis()) {
                calls.remove(owner.getUniqueId());
            } else {
                Entity entity = Bukkit.getEntity(call.targetId());
                if (entity instanceof LivingEntity target && isHostile(target)) {
                    double radius = plugin.settings().defendOwnerRadius();
                    if (bot.getLocation().distanceSquared(owner.getLocation()) <= radius * radius) {
                        if (ThreatRules.isSneakAvoid(target.getType())) {
                            calls.remove(owner.getUniqueId());
                            sneakAway(owner, bot, target);
                            return;
                        }
                        if (isAvoided(target)) {
                            calls.remove(owner.getUniqueId());
                            flee(owner, bot, target);
                            return;
                        }
                        engage(owner, bot, target, true);
                        return;
                    }
                }
                calls.remove(owner.getUniqueId());
            }
        }

        if (!plugin.profiles().autoDefense(owner)) {
            return;
        }
        LivingEntity target = findNearestHostile(bot, plugin.settings().defenseRadius());
        if (target != null) {
            if (isAvoided(target)) {
                flee(owner, bot, target);
                return;
            }
            engage(owner, bot, target, false);
        }
    }

    private boolean isAvoided(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (ThreatRules.isCreeper(entity.getType()) || ThreatRules.isSneakAvoid(entity.getType())) {
            return false;
        }
        return plugin.settings().avoidMobs().contains(entity.getType());
    }

    private LivingEntity findNearestAvoided(Bot bot, double radius) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : bot.getNearbyEntities(radius, radius, radius)) {
            if (!isHostile(entity) || !isAvoided(entity)) {
                continue;
            }
            double distance = entity.getLocation().distanceSquared(bot.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = (LivingEntity) entity;
            }
        }
        return best;
    }

    /**
     * 远离强力敌人，跑出一段距离后再恢复跟随。
     */
    private void flee(Player owner, Bot bot, LivingEntity threat) {
        UUID botId = bot.getUniqueId();
        if (!fleeing.add(botId)) {
            return;
        }
        if (plugin.settings().debug()) {
            plugin.getLogger().info("规避强敌：" + BotNames.rawName(bot) + " 远离 " + threat.getType().name());
        }
        Location safe = findSafeSpot(bot, threat, Math.max(8.0, plugin.settings().avoidRadius()));
        plugin.navigator().navigateAndWait(bot, safe, 2.0, 140)
                .whenComplete((arrived, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    fleeing.remove(botId);
                    cooldowns.put(botId, System.currentTimeMillis() + 2500L);
                    if (!bot.isValid() || !owner.isOnline()) {
                        return;
                    }
                    if (bot.getWorld() != null && bot.getWorld().equals(owner.getWorld())
                            && plugin.profiles().autoFollow(owner)
                            && !plugin.navigator().isBusy(bot)) {
                        plugin.navigator().follow(bot, owner, plugin.settings().followDistance());
                    }
                }));
    }

    private Location findSafeSpot(Bot bot, LivingEntity threat, double distance) {
        World world = bot.getWorld();
        if (world == null) {
            return bot.getLocation();
        }
        Location origin = bot.getLocation();
        Vector away = origin.toVector().subtract(threat.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.01) {
            away = new Vector(1, 0, 0);
        }
        away.normalize();
        double[] angles = {0, 30, -30, 60, -60, 90, -90, 135, -135, 180};
        for (double angle : angles) {
            Vector direction = away.clone().rotateAroundY(Math.toRadians(angle));
            Location base = origin.clone().add(direction.multiply(distance));
            Location ground = findGround(world, base);
            if (ground != null) {
                return ground;
            }
        }
        return origin.clone().add(away.multiply(distance));
    }

    private Location findGround(World world, Location base) {
        for (int dy = 3; dy >= -4; dy--) {
            Block feet = world.getBlockAt(base.getBlockX(), base.getBlockY() + dy, base.getBlockZ());
            Block head = feet.getRelative(BlockFace.UP);
            Block below = feet.getRelative(BlockFace.DOWN);
            if (feet.isPassable() && head.isPassable() && !below.isPassable() && below.getType().isSolid()) {
                return new Location(world, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            }
        }
        return null;
    }

    private LivingEntity findNearestHostile(Bot bot, double radius) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : bot.getNearbyEntities(radius, radius, radius)) {
            if (!isHostile(entity) || ThreatRules.isSneakAvoid(entity.getType())) {
                continue;
            }
            double distance = entity.getLocation().distanceSquared(bot.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = (LivingEntity) entity;
            }
        }
        return best;
    }

    private void engage(Player owner, Bot bot, LivingEntity target, boolean defendingOwner) {
        UUID botId = bot.getUniqueId();
        if (!attacking.add(botId)) {
            return;
        }
        if (plugin.settings().debug()) {
            plugin.getLogger().info("自动" + (defendingOwner ? "护主" : "反击") + "："
                    + BotNames.rawName(bot) + " -> " + target.getType().name());
        }
        plugin.botActions().attack(bot, target).whenComplete((result, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (isRetreatResult(result)) {
                        attacking.remove(botId);
                        cooldowns.put(botId, System.currentTimeMillis() + 6000L);
                        return;
                    }
                    if (target.isValid() && !target.isDead()) {
                        attacking.remove(botId);
                        // 目标还活着：短暂冷却后继续站撸，不恢复跟随
                        cooldowns.put(botId, System.currentTimeMillis() + 1500L);
                        return;
                    }
                    if (!bot.isValid()) {
                        attacking.remove(botId);
                        return;
                    }
                    // 击倒后自动拾取附近掉落物，再恢复跟随
                    plugin.botActions().collectItems(bot, bot.getLocation(),
                                    Math.max(3, plugin.settings().miningPickupRadius()), null, 60)
                            .whenComplete((count, collectError) ->
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        attacking.remove(botId);
                                        resumeFollow(owner, bot);
                                    }));
                }));
    }

    private boolean isRetreatResult(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        try {
            return Json.getBoolean(Json.parse(result), "retreat", false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void resumeFollow(Player owner, Bot bot) {
        if (bot == null || !bot.isValid() || owner == null || !owner.isOnline()) {
            return;
        }
        if (bot.getWorld() != null && bot.getWorld().equals(owner.getWorld())
                && plugin.profiles().autoFollow(owner)
                && !plugin.navigator().isBusy(bot)) {
            plugin.navigator().follow(bot, owner, plugin.settings().followDistance());
        }
    }

    /**
     * 坚守者：不交战，潜行离开（禁止奔跑）。
     */
    private void sneakAway(Player owner, Bot bot, LivingEntity warden) {
        UUID botId = bot.getUniqueId();
        if (!fleeing.add(botId)) {
            return;
        }
        if (plugin.settings().debug()) {
            plugin.getLogger().info("潜行离开坚守者：" + BotNames.rawName(bot));
        }
        plugin.botActions().retreatFrom(bot, warden, 12.0, true)
                .whenComplete((arrived, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    fleeing.remove(botId);
                    cooldowns.put(botId, System.currentTimeMillis() + 4000L);
                    resumeFollow(owner, bot);
                }));
    }

    /**
     * 被多只敌对生物围攻：撤退找窄道/高处。
     */
    private void groupRetreat(Player owner, Bot bot) {
        LivingEntity threat = findNearestHostile(bot, ThreatRules.GROUP_RADIUS);
        if (threat == null) {
            return;
        }
        UUID botId = bot.getUniqueId();
        if (!fleeing.add(botId)) {
            return;
        }
        if (plugin.settings().debug()) {
            plugin.getLogger().info("被围攻撤退：" + BotNames.rawName(bot));
        }
        plugin.botActions().retreatFrom(bot, threat, 12.0, false)
                .whenComplete((arrived, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    fleeing.remove(botId);
                    cooldowns.put(botId, System.currentTimeMillis() + 4000L);
                    resumeFollow(owner, bot);
                }));
    }

    private LivingEntity findNearestWarden(Bot bot, double radius) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : bot.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living)
                    || !ThreatRules.isSneakAvoid(entity.getType())
                    || !living.isValid() || living.isDead()) {
                continue;
            }
            double distance = entity.getLocation().distanceSquared(bot.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    private int countAttackers(Bot bot, double radius) {
        int count = 0;
        for (Entity entity : bot.getNearbyEntities(radius, radius, radius)) {
            if (isHostile(entity) && !ThreatRules.isSneakAvoid(entity.getType())) {
                count++;
            }
        }
        return count;
    }

    private Entity resolveDamager(Entity damager) {
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    private boolean isHostile(Entity entity) {
        if (!(entity instanceof Enemy enemy) || !enemy.isValid() || enemy.isDead()) {
            return false;
        }
        if (entity instanceof Tameable tameable && tameable.isTamed()) {
            return false;
        }
        if (ThreatRules.isPassive(entity.getType())) {
            return false;
        }
        return !(entity instanceof Player);
    }

    private record DefenseCall(UUID targetId, long expiresAt) {
    }
}
