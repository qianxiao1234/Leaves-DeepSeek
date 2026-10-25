package com.leavesdeepseek.buddy.bot;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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

    /** 远离苦力怕后需要保持安全的时长，之后才恢复跟随 */
    private static final long CREEPER_SAFE_MS = 5000L;
    private static final long CREEPER_FOLLOW_SUSPEND_MS = 60_000L;

    /** 被围攻 / 血量过低时撤离的距离与「安全半径」 */
    private static final double RETREAT_DISTANCE = 16.0;
    private static final double RETREAT_CLEAR_RADIUS = 12.0;
    /** 威胁离开 RETREAT_CLEAR_RADIUS 后还要安全这么久才算脱险 */
    private static final long RETREAT_SAFE_MS = 3000L;
    private static final long RETREAT_FOLLOW_SUSPEND_MS = 60_000L;

    /** 假人被打后的「记仇」时间与距离：远程怪可以超出 15 格索敌半径，照样要反击 */
    private static final long REVENGE_MS = 15_000L;
    private static final double REVENGE_RADIUS = 32.0;

    private final LeavesDeepSeekPlugin plugin;
    private final Set<UUID> attacking = ConcurrentHashMap.newKeySet();
    private final Set<UUID> fleeing = ConcurrentHashMap.newKeySet();
    /** 正在躲苦力怕的假人（与 fleeing 分开，因为要额外处理「安全 5 秒」） */
    private final Set<UUID> creeperFleeing = ConcurrentHashMap.newKeySet();
    /** 苦力怕离开后开始计时的时刻 */
    private final Map<UUID, Long> creeperSafeSince = new ConcurrentHashMap<>();
    /** 躲苦力怕之前是否在跟随 */
    private final Map<UUID, Boolean> creeperWasFollowing = new ConcurrentHashMap<>();
    /** 持续撤退中（被围攻 / 血量过低）：威胁没走就一直跑 */
    private final Set<UUID> retreating = ConcurrentHashMap.newKeySet();
    /** 假人自己被谁打了（远程怪站在 15 格外射箭时也要反击，否则会站着发呆） */
    private final Map<UUID, DefenseCall> revenge = new ConcurrentHashMap<>();
    /** 附近没有威胁后开始计时的时刻 */
    private final Map<UUID, Long> retreatSafeSince = new ConcurrentHashMap<>();
    /** 撤退时用来判断「是不是被堵住跑不动」的锚点与时间 */
    private final Map<UUID, Location> retreatAnchor = new ConcurrentHashMap<>();
    private final Map<UUID, Long> retreatAnchorAt = new ConcurrentHashMap<>();
    /** 下一次允许重新下发撤退导航的时刻（防止寻路失败时每 0.25 秒疯狂重算） */
    private final Map<UUID, Long> retreatNextAt = new ConcurrentHashMap<>();
    private final Map<UUID, DefenseCall> calls = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private BukkitTask task;
    private BukkitTask creeperTask;
    private BukkitTask retreatTask;

    public CombatGuardian(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long period = Math.max(10, plugin.settings().combatCheckIntervalTicks());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
        // 苦力怕单独用 5 tick（0.25 秒）检测：爆炸只有 1.5 秒引信，等 1 秒才反应太迟
        creeperTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCreepers, 5L, 5L);
        // 撤退要「一直跑」，不能靠一次导航 + 冷却，否则会跑一下、停一下（原地发呆）再跑
        retreatTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRetreats, 5L, 5L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (creeperTask != null) {
            creeperTask.cancel();
            creeperTask = null;
        }
        if (retreatTask != null) {
            retreatTask.cancel();
            retreatTask = null;
        }
        attacking.clear();
        fleeing.clear();
        creeperFleeing.clear();
        creeperSafeSince.clear();
        creeperWasFollowing.clear();
        retreating.clear();
        retreatSafeSince.clear();
        retreatAnchor.clear();
        retreatAnchorAt.clear();
        retreatNextAt.clear();
        revenge.clear();
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
        return attacking.contains(botId) || fleeing.contains(botId)
                || creeperFleeing.contains(botId) || retreating.contains(botId);
    }

    /** 是否正在持续撤退（被围攻 / 血量过低）。 */
    public boolean isRetreating(Bot bot) {
        return bot != null && retreating.contains(bot.getUniqueId());
    }

    /** 是否正在因为苦力怕而逃跑（给「跟着我」「自动进食」判断用）。 */
    public boolean isCreeperFleeing(Bot bot) {
        return bot != null && creeperFleeing.contains(bot.getUniqueId());
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

    /**
     * 假人自己挨打：记住打它的家伙，下一次检测立刻反击。
     * <p>没有这段的话，站在索敌半径（15 格）外射箭的骷髅打它，假人会一动不动地「发呆」。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Bot bot) || !bot.isValid()) {
            return;
        }
        UUID ownerId = plugin.bots().ownerOf(bot);
        if (ownerId == null) {
            return;
        }
        Entity damager = resolveDamager(event.getDamager());
        if (!isHostile(damager)) {
            return;
        }
        if (bot.getLocation().distanceSquared(damager.getLocation()) > REVENGE_RADIUS * REVENGE_RADIUS) {
            return;
        }
        revenge.put(bot.getUniqueId(),
                new DefenseCall(damager.getUniqueId(), System.currentTimeMillis() + REVENGE_MS));
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null && owner.isOnline()) {
                handleOwner(owner);
            }
        });
    }

    private void tick() {
        long now = System.currentTimeMillis();
        calls.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        revenge.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        for (Player player : Bukkit.getOnlinePlayers()) {
            handleOwner(player);
        }
    }

    /**
     * 苦力怕专用高频检测：看到就立刻放弃手上的活、疾跑远离，
     * 离开 10 格后保持 5 秒安全再恢复跟随。
     */
    private void tickCreepers() {
        try {
            long now = System.currentTimeMillis();
            if (!creeperFleeing.isEmpty()) {
                creeperFleeing.removeIf(botId -> {
                    Bot bot = plugin.bots().byId(botId);
                    return bot == null || !bot.isValid();
                });
                creeperSafeSince.keySet().retainAll(creeperFleeing);
                creeperWasFollowing.keySet().retainAll(creeperFleeing);
            }
            // 遍历所有有主人的假人（主人离线时也要躲苦力怕）
            for (Bot bot : plugin.bots().allBots()) {
                if (bot == null || !bot.isValid() || bot.isDead() || bot.isSleeping()
                        || plugin.bots().isDowned(bot)) {
                    continue;
                }
                UUID ownerId = plugin.bots().ownerOf(bot);
                if (ownerId == null) {
                    continue;
                }
                handleCreeper(Bukkit.getPlayer(ownerId), bot, now);
            }
        } catch (Throwable throwable) {
            plugin.log().error("战斗", "苦力怕规避检查出错：" + throwable.getMessage(), throwable);
        }
    }

    /**
     * @return true 表示这一轮由苦力怕规避接管（不要再处理其它战斗目标）
     */
    private boolean handleCreeper(Player owner, Bot bot, long now) {
        UUID botId = bot.getUniqueId();
        LivingEntity creeper = findNearestCreeper(bot, ThreatRules.CREEPER_DETECT_RADIUS);
        if (creeper != null) {
            creeperSafeSince.remove(botId);
            boolean first = creeperFleeing.add(botId);
            if (first) {
                boolean wasFollowing = plugin.navigator().hasFollowIntent(bot);
                creeperWasFollowing.put(botId, wasFollowing);
                // 先停掉挖矿/战斗，再暂停跟随，最后才疾跑（顺序不能反：interrupt 会把暂停时长改回 4 秒）
                plugin.botActions().interrupt(bot, "看到苦力怕，先跑");
                plugin.log().info("战斗", "躲苦力怕 | 假人=" + BotNames.rawName(bot)
                        + " | 距离=" + Math.round(bot.getLocation().distance(creeper.getLocation()))
                        + "格 | 跟随中=" + wasFollowing);
            }
            // 苦力怕还在：持续把跟随挡在外面（主人中途说「跟着我」也不会往回走）
            plugin.navigator().suspendFollow(bot, CREEPER_FOLLOW_SUSPEND_MS);
            if (!plugin.navigator().isNavigating(bot)) {
                fleeCreeper(bot, creeper);
            }
            return true;
        }

        if (!creeperFleeing.contains(botId)) {
            return false;
        }
        long since = creeperSafeSince.computeIfAbsent(botId, key -> now);
        if (now - since < CREEPER_SAFE_MS) {
            // 已经远离苦力怕，但还要再等 5 秒确认安全；期间保持跟随暂停，不急着往回走
            plugin.navigator().suspendFollow(bot, CREEPER_FOLLOW_SUSPEND_MS);
            return true;
        }
        creeperFleeing.remove(botId);
        creeperSafeSince.remove(botId);
        boolean wasFollowing = Boolean.TRUE.equals(creeperWasFollowing.remove(botId));
        boolean wantsFollow = wasFollowing || plugin.navigator().hasFollowIntent(bot);
        if (wantsFollow && owner != null && owner.isOnline()
                && bot.getWorld() != null && bot.getWorld().equals(owner.getWorld())) {
            plugin.navigator().follow(bot, owner, plugin.settings().followDistance());
            plugin.log().info("战斗", "苦力怕已远离 5 秒，恢复跟随 | 假人=" + BotNames.rawName(bot));
        }
        return true;
    }

    /** 朝远离苦力怕的方向疾跑；由 tickCreepers 负责后续与恢复跟随。 */
    private void fleeCreeper(Bot bot, LivingEntity creeper) {
        Location safe = findSafeSpot(bot, creeper, ThreatRules.CREEPER_FLEE_DISTANCE);
        bot.setSprinting(true);
        plugin.navigator().navigateAndWait(bot, safe, 1.5, 140, BotNavigator.PRIORITY_HIGH)
                .whenComplete((arrived, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (bot.isValid()) {
                        bot.setSprinting(false);
                    }
                }));
    }

    private void handleOwner(Player owner) {
        if (!owner.isOnline()) {
            return;
        }
        Bot bot = plugin.bots().primaryBot(owner);
        if (bot == null || !bot.isValid() || bot.isDead() || bot.isSleeping() || plugin.bots().isDowned(bot)) {
            return;
        }
        UUID botId = bot.getUniqueId();
        // 苦力怕由 tickCreepers（0.25 秒一次）全权接管，别再抢目标
        if (creeperFleeing.contains(botId)
                || findNearestCreeper(bot, ThreatRules.CREEPER_DETECT_RADIUS) != null) {
            return;
        }
        if (attacking.contains(botId) || fleeing.contains(botId)) {
            return;
        }
        // 持续撤退由 tickRetreats 全权负责，这里别再抢（否则会出现「跑一下、停一下」）
        if (retreating.contains(botId)) {
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

        // 被 4 只以上围攻 / 血量过低：交给 tickRetreats 持续撤离（这里不再一次性撤退 + 冷却发呆）
        if (needsRetreat(bot)) {
            startRetreat(bot);
            return;
        }

        // 配置里额外不交战的生物
        LivingEntity avoided = findNearestAvoided(bot, plugin.settings().avoidRadius());
        if (avoided != null) {
            flee(owner, bot, avoided);
            return;
        }

        // 谁打我我就打谁（远程怪 15 格外也照样冲过去，不然会站着发呆）
        DefenseCall revengeCall = revenge.get(botId);
        if (revengeCall != null) {
            if (revengeCall.expiresAt() < System.currentTimeMillis()) {
                revenge.remove(botId);
            } else {
                Entity attacker = Bukkit.getEntity(revengeCall.targetId());
                if (attacker instanceof LivingEntity living && isHostile(living)) {
                    if (bot.getWorld() != null && bot.getWorld().equals(living.getWorld())) {
                        // 血还够就打回去；血量低时上面的 needsRetreat 已经接管
                        if (bot.getHealth() >= bot.getMaxHealth() * ThreatRules.HEALTH_RETREAT_RATIO) {
                            engage(owner, bot, living, false);
                            return;
                        }
                        return;
                    }
                }
                revenge.remove(botId);
            }
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
        // 血量太低就别再主动开战了（打了也是被秒；撤离交给 tickRetreats）
        if (bot.getHealth() < bot.getMaxHealth() * ThreatRules.HEALTH_RETREAT_RATIO) {
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
                            && plugin.navigator().hasFollowIntent(bot)
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
            if (!isHostile(entity) || ThreatRules.isSneakAvoid(entity.getType())
                    || ThreatRules.isCreeper(entity.getType())) {
                // 苦力怕不列入攻击目标，走上面的主动规避逻辑
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
                        // 撤退交给 tickRetreats 持续处理，这里只做很短的冷却，避免「撤退完发呆 6 秒」
                        cooldowns.put(botId, System.currentTimeMillis() + 2000L);
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
        // 正在躲苦力怕：由 tickCreepers 在「安全 5 秒」后统一恢复，别抢先跟回去
        if (isCreeperFleeing(bot)) {
            return;
        }
        if (bot.getWorld() != null && bot.getWorld().equals(owner.getWorld())
                && plugin.navigator().hasFollowIntent(bot)
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
     * 被围攻 / 血量过低时的持续撤退。
     * <p>旧实现是「一次导航到 12 格外的安全点 → 4~6 秒冷却」，冷却期间 {@code handleOwner} 直接 return，
     * 表现就是「跑一下 → 原地发呆 → 再跑一下」，被僵尸围着打几轮就死了。现在改成状态机：
     * 只要 8 格内还有 ≥4 只敌对生物（或血量低且身边有怪）就一直跑，每 0.25 秒补一次导航；
     * 威胁离开 12 格并保持 3 秒才收手，然后按跟随意图决定要不要回去。</p>
     */
    private void tickRetreats() {
        try {
            long now = System.currentTimeMillis();
            retreating.removeIf(botId -> {
                Bot bot = plugin.bots().byId(botId);
                return bot == null || !bot.isValid() || bot.isDead() || plugin.bots().isDowned(bot);
            });
            retreatSafeSince.keySet().retainAll(retreating);

            // 1) 已经在撤退：跑；没威胁了开始计时
            for (UUID botId : new java.util.ArrayList<>(retreating)) {
                Bot bot = plugin.bots().byId(botId);
                if (bot == null) {
                    continue;
                }
                LivingEntity threat = findNearestHostile(bot, RETREAT_CLEAR_RADIUS);
                if (threat != null) {
                    // 有苦力怕靠近：交给 tickCreepers 的高频规避（爆炸比僵尸危险得多）
                    if (findNearestCreeper(bot, ThreatRules.CREEPER_DETECT_RADIUS) != null) {
                        clearRetreatState(botId);
                        continue;
                    }
                    retreatSafeSince.remove(botId);
                    // 跟随别把假人拉回怪堆里（interrupt 会把暂停改回 4 秒，所以每轮都补）
                    plugin.navigator().suspendFollow(bot, RETREAT_FOLLOW_SUSPEND_MS);
                    if (stuckWhileRetreating(bot, now)) {
                        // 被堵住/没路可跑：站着也是挨打，就地反击
                        giveUpRetreat(bot);
                        continue;
                    }
                    Long nextAt = retreatNextAt.get(botId);
                    if (!plugin.navigator().isNavigating(bot)
                            && (nextAt == null || nextAt <= now)) {
                        retreatNextAt.put(botId, now + 300L);
                        plugin.botActions().retreatFrom(bot, threat, RETREAT_DISTANCE, false);
                    }
                    continue;
                }
                long since = retreatSafeSince.computeIfAbsent(botId, key -> now);
                plugin.navigator().suspendFollow(bot, RETREAT_FOLLOW_SUSPEND_MS);
                if (now - since >= RETREAT_SAFE_MS) {
                    finishRetreat(bot);
                }
            }

            // 2) 新进入：被围攻或血量过低
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.isOnline()) {
                    continue;
                }
                Bot bot = plugin.bots().primaryBot(player);
                if (bot == null || !bot.isValid() || bot.isDead() || bot.isSleeping()
                        || plugin.bots().isDowned(bot)) {
                    continue;
                }
                UUID botId = bot.getUniqueId();
                if (retreating.contains(botId) || fleeing.contains(botId)
                        || creeperFleeing.contains(botId)
                        || findNearestCreeper(bot, ThreatRules.CREEPER_DETECT_RADIUS) != null) {
                    // 苦力怕 / 坚守者 / 自定义规避生物有自己的流程，别抢
                    continue;
                }
                if (!needsRetreat(bot)) {
                    continue;
                }
                startRetreat(bot);
            }
        } catch (Throwable throwable) {
            plugin.log().error("战斗", "撤退检查出错：" + throwable.getMessage(), throwable);
        }
    }

    /**
     * 什么时候该跑。
     * <p>用户选定的策略是「站撸到残血再纯跑」：血量 ≥30% 时不管被几只围住都打，
     * 只有血量掉到 30% 以下、且身边还有敌对生物时才撤退（撤退由状态机持续执行）。</p>
     */
    private boolean needsRetreat(Bot bot) {
        double ratio = bot.getHealth() / Math.max(1.0, bot.getMaxHealth());
        if (ratio >= ThreatRules.HEALTH_RETREAT_RATIO) {
            return false;
        }
        return countAttackers(bot, ThreatRules.GROUP_RADIUS) >= 1;
    }

    private void startRetreat(Bot bot) {
        UUID botId = bot.getUniqueId();
        if (!retreating.add(botId)) {
            return;
        }
        retreatSafeSince.remove(botId);
        retreatAnchor.put(botId, bot.getLocation().clone());
        retreatAnchorAt.put(botId, System.currentTimeMillis());
        int attackers = countAttackers(bot, ThreatRules.GROUP_RADIUS);
        boolean following = plugin.navigator().hasFollowIntent(bot);
        // 顺序不能反：interrupt() 内部会 cancel() 把跟随暂停改成 4 秒，必须之后再 suspend
        plugin.botActions().interrupt(bot, "被围攻/血量过低，先撤");
        plugin.navigator().suspendFollow(bot, RETREAT_FOLLOW_SUSPEND_MS);
        plugin.log().info("战斗", "撤退 | 假人=" + BotNames.rawName(bot)
                + " | 附近敌人=" + attackers
                + " | 血量=" + Math.round(bot.getHealth() * 10) / 10.0
                + " | 跟随中=" + following);
        LivingEntity threat = findNearestHostile(bot, RETREAT_CLEAR_RADIUS);
        if (threat != null) {
            plugin.botActions().retreatFrom(bot, threat, RETREAT_DISTANCE, false);
        }
    }

    private void finishRetreat(Bot bot) {
        UUID botId = bot.getUniqueId();
        clearRetreatState(botId);
        // 刚脱险先别马上又冲回去
        cooldowns.put(botId, System.currentTimeMillis() + 3000L);
        plugin.log().info("战斗", "已脱离危险 | 假人=" + BotNames.rawName(bot)
                + " | 血量=" + Math.round(bot.getHealth() * 10) / 10.0);
        // 自动进食只管饥饿，血量还得自己补（受伤后饥饿值往往还是满的）
        double ratio = bot.getHealth() / Math.max(1.0, bot.getMaxHealth());
        if (ratio < ThreatRules.HEALTH_EAT_RATIO && !plugin.botActions().isEating(bot)) {
            Material food = FoodRules.pickEmergency(bot.getInventory(), bot.getFoodLevel() >= 20);
            if (food != null) {
                plugin.botActions().eat(bot, food, true);
            }
        }
        UUID ownerId = plugin.bots().ownerOf(bot);
        resumeFollow(ownerId == null ? null : Bukkit.getPlayer(ownerId), bot);
    }

    /**
     * 撤退中原地打转超过 3.5 秒（被怪围住的死角、没路可跑）：判定为跑不掉。
     */
    private boolean stuckWhileRetreating(Bot bot, long now) {
        UUID botId = bot.getUniqueId();
        Location anchor = retreatAnchor.get(botId);
        if (anchor == null || anchor.getWorld() != bot.getWorld()) {
            retreatAnchor.put(botId, bot.getLocation().clone());
            retreatAnchorAt.put(botId, now);
            return false;
        }
        if (anchor.distanceSquared(bot.getLocation()) > 2.25) {
            retreatAnchor.put(botId, bot.getLocation().clone());
            retreatAnchorAt.put(botId, now);
            return false;
        }
        Long since = retreatAnchorAt.get(botId);
        return since != null && now - since > 3500L;
    }

    /** 跑不掉了：放弃撤退、清除暂停跟随，让正常战斗逻辑就地反击。 */
    private void giveUpRetreat(Bot bot) {
        UUID botId = bot.getUniqueId();
        clearRetreatState(botId);
        cooldowns.put(botId, System.currentTimeMillis() + 1000L);
        plugin.log().info("战斗", "跑不掉了，就地反击 | 假人=" + BotNames.rawName(bot)
                + " | 血量=" + Math.round(bot.getHealth() * 10) / 10.0);
    }

    private void clearRetreatState(UUID botId) {
        retreating.remove(botId);
        retreatSafeSince.remove(botId);
        retreatAnchor.remove(botId);
        retreatAnchorAt.remove(botId);
        retreatNextAt.remove(botId);
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

    private LivingEntity findNearestCreeper(Bot bot, double radius) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : bot.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living)
                    || !ThreatRules.isCreeper(entity.getType())
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
