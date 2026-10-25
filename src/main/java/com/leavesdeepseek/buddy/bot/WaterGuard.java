package com.leavesdeepseek.buddy.bot;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 水域生存：
 * <ol>
 *   <li>任何时候快没气都先浮上水面（跟着主人也一样，不能把自己憋死）；</li>
 *   <li>不在跟随时，掉进水里会主动找最近的上岸点，而不是一直泡着；</li>
 *   <li>跟着主人时允许下水（跟随由 BotNavigator 处理），只在缺氧时临时上浮。</li>
 * </ol>
 */
public final class WaterGuard {

    /** 剩余空气低于该值就开始上浮（20 tick = 1 秒） */
    private static final int AIR_WARN_TICKS = 120;
    /** 找上岸点的半径 */
    private static final int SHORE_RADIUS = 10;
    /** 两次上岸尝试之间的冷却 */
    private static final long SHORE_COOLDOWN_MS = 3000L;
    /** 上浮期间暂停跟随的时长（完成后会主动恢复） */
    private static final long SURFACE_FOLLOW_SUSPEND_MS = 30_000L;

    private final LeavesDeepSeekPlugin plugin;
    private final Set<UUID> surfacing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> shoreCooldowns = new ConcurrentHashMap<>();

    private BukkitTask task;

    public WaterGuard(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        surfacing.clear();
        shoreCooldowns.clear();
    }

    private void tick() {
        try {
            checkAll();
        } catch (Throwable throwable) {
            plugin.log().error("生存", "水域检查出错：" + throwable.getMessage(), throwable);
        }
    }

    private void checkAll() {
        long now = System.currentTimeMillis();
        // 遍历所有有主人的假人（主人离线时也不能淹死）
        for (Bot bot : plugin.bots().allBots()) {
            if (bot == null || !bot.isValid() || bot.isDead() || bot.isSleeping()
                    || plugin.bots().isDowned(bot)) {
                continue;
            }
            UUID ownerId = plugin.bots().ownerOf(bot);
            if (ownerId == null) {
                continue;
            }
            Player owner = Bukkit.getPlayer(ownerId);
            UUID botId = bot.getUniqueId();
            if (surfacing.contains(botId)) {
                continue;
            }

            int air = bot.getRemainingAir();
            int maxAir = Math.max(1, bot.getMaximumAir());
            // 1) 缺氧：无论是否跟随，都先浮上去
            if (bot.isUnderWater() && air <= Math.min(AIR_WARN_TICKS, maxAir / 2)) {
                surface(bot, owner, air, maxAir);
                continue;
            }

            if (!bot.isInWater()) {
                continue;
            }
            // 2) 跟着主人时允许下水，不强制上岸（主人离线时按「没跟随」处理，安全上岸）
            if (plugin.navigator().hasFollowIntent(bot) && owner != null && owner.isOnline()
                    && bot.getWorld() != null && bot.getWorld().equals(owner.getWorld())) {
                continue;
            }
            // 3) 正在战斗/逃跑/进食/做别的事时不抢导航
            if (plugin.combatGuardian() != null && plugin.combatGuardian().isEngaged(bot)) {
                continue;
            }
            if (plugin.botActions().isEating(bot) || plugin.navigator().isBusy(bot)) {
                continue;
            }
            Long until = shoreCooldowns.get(botId);
            if (until != null && until > now) {
                continue;
            }
            Location shore = findShore(bot, SHORE_RADIUS);
            if (shore == null) {
                continue;
            }
            shoreCooldowns.put(botId, now + SHORE_COOLDOWN_MS);
            plugin.log().info("生存", "在水里，先上岸 | 假人=" + BotNames.rawName(bot)
                    + " | 目标=" + shore.getBlockX() + "," + shore.getBlockY() + "," + shore.getBlockZ());
            bot.setSprinting(true);
            plugin.navigator().navigateAndWait(bot, shore, 1.2, 140, BotNavigator.PRIORITY_HIGH)
                    .whenComplete((arrived, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!bot.isValid()) {
                            return;
                        }
                        bot.setSprinting(false);
                        if (bot.isInWater()) {
                            // 导航停在岸边但还泡在水里：给一个向上+向岸的速度，帮它爬上去
                            plugin.botActions().climbOutOfWater(bot, shore)
                                    .whenComplete((climbed, climbError) -> {
                                        if (Boolean.FALSE.equals(climbed) && plugin.log() != null) {
                                            plugin.log().info("生存", "上岸受阻，稍后重试 | 假人="
                                                    + BotNames.rawName(bot));
                                        }
                                    });
                        }
                    }));
        }
    }

    private void surface(Bot bot, Player owner, int air, int maxAir) {
        UUID botId = bot.getUniqueId();
        if (!surfacing.add(botId)) {
            return;
        }
        boolean following = plugin.navigator().hasFollowIntent(bot);
        plugin.navigator().cancel(bot);
        plugin.navigator().suspendFollow(bot, SURFACE_FOLLOW_SUSPEND_MS);
        plugin.log().info("生存", "水下快没气了，先浮上去 | 假人=" + BotNames.rawName(bot)
                + " | 空气=" + air + "/" + maxAir + " | 跟随中=" + following);
        plugin.botActions().surface(bot).whenComplete((done, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    surfacing.remove(botId);
                    if (!bot.isValid()) {
                        return;
                    }
                    if (Boolean.FALSE.equals(done) && bot.isUnderWater()
                            && bot.getRemainingAir() < 40) {
                        // 兜底：万一速度推进被服务端物理吃掉，也绝不能让假人淹死
                        bot.setRemainingAir(bot.getMaximumAir());
                        plugin.log().warn("生存", "上浮失败，已临时补满氧气避免淹死 | 假人="
                                + BotNames.rawName(bot));
                    }
                    if (!following || owner == null || !owner.isOnline()) {
                        return;
                    }
                    if (bot.getWorld() != null && bot.getWorld().equals(owner.getWorld())) {
                        // 恢复跟随（若主人已经说了「别跟着」，followDisabled 会拦住）
                        plugin.navigator().follow(bot, owner, plugin.settings().followDistance());
                    }
                }));
    }

    /**
     * 在附近找一块「脚和头都是空气、脚下是实体方块」的干地。
     */
    private Location findShore(Bot bot, int radius) {
        World world = bot.getWorld();
        if (world == null) {
            return null;
        }
        Location origin = bot.getLocation();
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        int baseX = origin.getBlockX();
        int baseY = origin.getBlockY();
        int baseZ = origin.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = 2; dy >= -3; dy--) {
                    Block feet = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                    Block head = feet.getRelative(BlockFace.UP);
                    Block below = feet.getRelative(BlockFace.DOWN);
                    if (feet.isLiquid() || head.isLiquid() || below.isLiquid()) {
                        continue;
                    }
                    if (!feet.isPassable() || !head.isPassable()) {
                        continue;
                    }
                    if (!below.getType().isSolid()) {
                        continue;
                    }
                    double distance = feet.getLocation().distanceSquared(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new Location(world, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                    }
                }
            }
        }
        return best;
    }
}
