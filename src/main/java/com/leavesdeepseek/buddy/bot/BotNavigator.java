package com.leavesdeepseek.buddy.bot;

import com.leavesdeepseek.buddy.config.PluginSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.entity.bot.action.BotAction;
import org.leavesmc.leaves.entity.bot.action.JumpAction;
import org.leavesmc.leaves.entity.bot.action.MoveAction;
import org.leavesmc.leaves.entity.bot.action.MoveAction.MoveDirection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * 假人导航：A* 寻路 + 玩家式输入（MoveAction 前进 / JumpAction 跳跃 / 转向）。
 */
public final class BotNavigator {

    /** 空闲走动的优先级（最低） */
    public static final int PRIORITY_IDLE = 10;
    /** AI 内部动作（走到方块旁边、捡东西）的优先级 */
    public static final int PRIORITY_NORMAL = 50;
    /** 主人明确下达的指令（跟着我 / 过来 / move_to）的优先级，会抢占低优先级任务 */
    public static final int PRIORITY_HIGH = 100;

    /** 每 2 tick 转向一次，单次最多转多少度（限制转身速度，避免抖动） */
    private static final float MAX_TURN_DEGREES = 22.0f;
    /** 朝向偏差超过该角度时先原地转身，不按前进键（避免边转边走导致原地打转） */
    private static final float MOVE_ALIGN_DEGREES = 55.0f;
    /** 跟随滞回：进入 follow-distance 内停下，超出 follow-distance + 该值才开始走 */
    private static final double FOLLOW_HYSTERESIS = 1.5;
    /** cancel() 之后暂停多久再自动恢复跟随 */
    private static final long FOLLOW_PAUSE_MS = 4000L;
    /** 正前方地形探测距离（略大于 1 格，避免贴到墙才反应） */
    private static final double PROBE_DISTANCE = 0.9;
    /** 贴墙时向侧面挪的持续 tick */
    private static final int SIDESTEP_TICKS = 10;
    /** 完全卡死时先后退的 tick，再重新寻路 */
    private static final int BACKOFF_TICKS = 8;

    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final BotService bots;
    private final Map<UUID, NavTask> tasks = new HashMap<>();
    /** 粘性跟随：只要没被 stopFollow 清掉，任何任务结束后都会自动恢复跟随 */
    private final Map<UUID, FollowState> follows = new HashMap<>();
    /** 主人明确说过「别跟着我」：屏蔽自动来源的 follow，只有 forceFollow 才能重新开启 */
    private final java.util.Set<UUID> followDisabled = new java.util.HashSet<>();
    /** 正在执行具体动作的假人：锁住期间不自动恢复跟随 */
    private final Map<UUID, Integer> locks = new HashMap<>();

    private BukkitTask ticker;

    /** 正前方地形探测结果 */
    private enum Ahead {
        CLEAR, STEP_UP, BLOCKED
    }

    public BotNavigator(JavaPlugin plugin, PluginSettings settings, BotService bots) {
        this.plugin = plugin;
        this.settings = settings;
        this.bots = bots;
    }

    public void start() {
        if (ticker != null) {
            return;
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 2L);
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (NavTask task : new ArrayList<>(tasks.values())) {
            task.cancelMovement();
            task.future.complete(false);
        }
        tasks.clear();
        follows.clear();
        followDisabled.clear();
    }

    /**
     * 前往目标并等待到达。必须在主线程调用。
     */
    public CompletableFuture<Boolean> navigateAndWait(Bot bot, Location target, double tolerance, int timeoutTicks) {
        return navigateAndWait(bot, target, tolerance, timeoutTicks, PRIORITY_NORMAL);
    }

    /**
     * 前往目标并等待到达，可指定优先级。高优先级会抢占低优先级任务；被更高优先级占用时返回 false。
     */
    public CompletableFuture<Boolean> navigateAndWait(Bot bot, Location target, double tolerance,
                                                      int timeoutTicks, int priority) {
        if (bot == null || target == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (bots.isDowned(bot)) {
            return CompletableFuture.completedFuture(false);
        }
        if (!claim(bot, priority)) {
            return CompletableFuture.completedFuture(false);
        }
        NavTask task = new NavTask(bot, target.clone(), Math.max(0.6, tolerance), timeoutTicks, null, priority);
        tasks.put(bot.getUniqueId(), task);
        task.plan();
        return task.future;
    }

    /**
     * 持续跟随玩家（粘性：任务结束后自动恢复，直到 stopFollow）。
     */
    public void follow(Bot bot, Player target, double distance) {
        follow(bot, target, distance, PRIORITY_NORMAL);
    }

    /**
     * 高优先级跟随：主人说「跟着我」时使用，会中断当前的移动与挖掘，并清除「别跟着」状态。
     */
    public void forceFollow(Bot bot, Player target, double distance) {
        if (bot != null) {
            followDisabled.remove(bot.getUniqueId());
        }
        follow(bot, target, distance, PRIORITY_HIGH);
    }

    /**
     * 只记下「要跟随」这件事，不立刻启动导航。
     * <p>用于主人说「跟着我」但假人正在强制进食：那口饭吃完（{@code endJob}）后
     * 会自动接着跟随。优先级仍然是 HIGH。</p>
     */
    public void deferForceFollow(Bot bot, Player target, double distance) {
        if (bot == null || target == null) {
            return;
        }
        UUID botId = bot.getUniqueId();
        followDisabled.remove(botId);
        follows.put(botId, new FollowState(target.getUniqueId(), Math.max(1.0, distance),
                PRIORITY_HIGH, 0L));
    }

    private void follow(Bot bot, Player target, double distance, int priority) {
        if (bot == null || target == null) {
            return;
        }
        UUID botId = bot.getUniqueId();
        if (priority < PRIORITY_HIGH && followDisabled.contains(botId)) {
            // 主人说过「别跟着我」，战斗/护主等自动来源不能再把跟随打开
            return;
        }
        FollowState previous = follows.get(botId);
        if (previous != null && previous.ownerId().equals(target.getUniqueId())) {
            // 不要把手动开启的高优先级跟随降级成普通跟随
            priority = Math.max(priority, previous.priority());
        }
        FollowState state = new FollowState(target.getUniqueId(), Math.max(1.0, distance), priority, 0L);
        follows.put(botId, state);

        if (bots.isDowned(bot)) {
            // 倒地时不启动导航，只记住「要跟随」，复活后自动接上
            return;
        }

        if (locks.containsKey(botId) && priority < PRIORITY_HIGH) {
            // 正在挖/砍/吃，等动作结束后由 unlockFollow 接上跟随
            return;
        }

        NavTask current = tasks.get(botId);
        if (current != null && !current.following() && current.priority > priority) {
            // 正在执行更高优先级的任务，等它结束后会自动继续跟随
            return;
        }
        if (current != null && current.following()
                && priority <= current.priority
                && target.getUniqueId().equals(current.followTarget)
                && Math.abs(current.tolerance - state.distance()) < 0.01) {
            return;
        }
        startFollow(bot, state);
    }

    /**
     * 当前是否处于跟随状态（包括正在执行其他任务、稍后会自动恢复跟随的情况）。
     */
    public boolean isFollowing(Bot bot) {
        if (bot == null) {
            return false;
        }
        FollowState state = follows.get(bot.getUniqueId());
        return state != null && !state.paused();
    }

    /**
     * 彻底停止跟随（主人说「停下」/ stop_all_actions / /buddy move stop 时使用）。
     * <p>会记住「主人让我别跟着」，之后战斗结束、任务结束都不会自动恢复跟随；
     * 只有 forceFollow（主人明确说「跟着我」或 /buddy move follow）才会重新开启。</p>
     */
    public void stopFollow(Bot bot) {
        if (bot == null) {
            return;
        }
        UUID botId = bot.getUniqueId();
        followDisabled.add(botId);
        follows.remove(botId);
        NavTask task = tasks.get(botId);
        if (task != null && task.following()) {
            tasks.remove(botId);
            task.cancelMovement();
            task.future.complete(false);
        }
    }

    /**
     * 只清掉跟随状态，**不**写「别跟着」标记。
     * <p>给 AI 躯壳用：它们没有主人、跟随只是某个任务，任务结束/暂停时必须彻底停掉，
     * 否则看门狗会把跟随又接回去（躯壳在旁观态还会自己走开）。传统假人不要用这个。</p>
     */
    public void abortFollow(Bot bot) {
        if (bot == null) {
            return;
        }
        UUID botId = bot.getUniqueId();
        follows.remove(botId);
        NavTask task = tasks.get(botId);
        if (task != null && task.following()) {
            tasks.remove(botId);
            task.cancelMovement();
            task.future.complete(false);
        }
    }

    /**
     * 暂停粘性跟随一段时间（不写 followDisabled）。
     * <p>用于「有苦力怕先跑开」「水下先浮上来换气」这类临时避险：到点后
     * {@code resumeFollow} 会自动接上；如果这期间主人说了「跟着我」，
     * {@code forceFollow} 会直接覆盖暂停状态。</p>
     */
    public void suspendFollow(Bot bot, long millis) {
        if (bot == null) {
            return;
        }
        UUID botId = bot.getUniqueId();
        FollowState state = follows.get(botId);
        if (state != null) {
            follows.put(botId, state.pause(Math.max(0L, millis)));
        }
        NavTask task = tasks.get(botId);
        if (task != null && task.following()) {
            tasks.remove(botId);
            task.cancelMovement();
            task.future.complete(false);
        }
    }

    /**
     * 是否「想跟着主人」（粘性跟随状态存在，即使因为躲苦力怕/换气被临时暂停）。
     */
    public boolean hasFollowIntent(Bot bot) {
        return bot != null && follows.containsKey(bot.getUniqueId())
                && !followDisabled.contains(bot.getUniqueId());
    }

    /**
     * 主人是否明确让假人「别跟着」。
     */
    public boolean isFollowDisabled(Bot bot) {
        return bot != null && followDisabled.contains(bot.getUniqueId());
    }

    /**
     * 执行具体动作（挖掘/放置/吃/砍树）期间锁住跟随，避免跟随逻辑抢走转向与移动键。
     * 可重入：每调用一次要配对一次 {@link #unlockFollow(Bot)}。
     */
    public void lockFollow(Bot bot) {
        if (bot == null) {
            return;
        }
        UUID botId = bot.getUniqueId();
        locks.merge(botId, 1, Integer::sum);
        // 有动作要独占移动/转向时，先把跟随任务挂起（动作结束后 unlockFollow 会自动接上）
        NavTask task = tasks.get(botId);
        if (task != null && task.following()) {
            tasks.remove(botId);
            task.cancelMovement();
            task.future.complete(false);
        }
    }

    public void unlockFollow(Bot bot) {
        if (bot == null) {
            return;
        }
        UUID botId = bot.getUniqueId();
        Integer count = locks.get(botId);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            locks.remove(botId);
            resumeFollow(bot);
        } else {
            locks.put(botId, count - 1);
        }
    }

    public boolean isFollowLocked(Bot bot) {
        return bot != null && locks.containsKey(bot.getUniqueId());
    }

    /**
     * 主人下达新指令时清空锁，让跟随能立刻恢复。
     */
    public void clearFollowLocks(Bot bot) {
        if (bot != null) {
            locks.remove(bot.getUniqueId());
        }
    }

    private void startFollow(Bot bot, FollowState state) {
        UUID botId = bot.getUniqueId();
        NavTask current = tasks.remove(botId);
        if (current != null) {
            current.cancelMovement();
            current.future.complete(false);
        }
        NavTask task = new NavTask(bot, null, Math.max(1.0, state.distance()), Integer.MAX_VALUE,
                state.ownerId(), state.priority());
        tasks.put(botId, task);
        task.plan();
    }

    /**
     * 任务结束（正常/失败）后恢复粘性跟随。
     */
    private void resumeFollow(Bot bot) {
        UUID botId = bot.getUniqueId();
        if (tasks.containsKey(botId) || locks.containsKey(botId)) {
            return;
        }
        FollowState state = follows.get(botId);
        if (state == null) {
            return;
        }
        if (state.paused()) {
            return;
        }
        if (!bot.isValid()) {
            return;
        }
        startFollow(bot, state);
    }

    /**
     * 尝试占用该假人的导航权。已有更高优先级的任务时返回 false。
     */
    private boolean claim(Bot bot, int priority) {
        NavTask current = tasks.get(bot.getUniqueId());
        if (current == null) {
            return true;
        }
        if (current.priority > priority) {
            return false;
        }
        tasks.remove(bot.getUniqueId());
        current.cancelMovement();
        current.future.complete(false);
        return true;
    }

    /**
     * 只启动导航，不等待结果。
     */
    public CompletableFuture<Boolean> navigate(Bot bot, Location target, double tolerance, int timeoutTicks) {
        return navigateAndWait(bot, target, tolerance, timeoutTicks, PRIORITY_NORMAL);
    }

    public boolean isNavigating(Bot bot) {
        return bot != null && tasks.containsKey(bot.getUniqueId());
    }

    /** Whether the current navigation task is actively issuing movement. */
    public boolean isMoving(Bot bot) {
        NavTask task = bot == null ? null : tasks.get(bot.getUniqueId());
        return task != null && task.moving;
    }

    /**
     * 是否正在空闲随机走动。
     */
    public boolean isWandering(Bot bot) {
        NavTask task = bot == null ? null : tasks.get(bot.getUniqueId());
        return task != null && task.wandering;
    }

    /**
     * 是否有非跟随类的导航任务（例如 move_to 正在执行）。
     */
    public boolean isBusy(Bot bot) {
        NavTask task = bot == null ? null : tasks.get(bot.getUniqueId());
        return task != null && !task.following();
    }

    /**
     * 空闲时小范围随机走动：不会打断跟随，只是让跟随任务临时去一个近处目标。
     */
    public void wander(Bot bot, Location target, int timeoutTicks) {
        if (bot == null || target == null || locks.containsKey(bot.getUniqueId())) {
            return;
        }
        NavTask task = tasks.get(bot.getUniqueId());
        if (task == null) {
            // 跟随任务还没接上时交给看门狗处理，不要和跟随抢方向
            if (follows.containsKey(bot.getUniqueId())) {
                return;
            }
            NavTask newTask = new NavTask(bot, target.clone(), 0.8, Math.max(40, timeoutTicks), null, PRIORITY_IDLE);
            tasks.put(bot.getUniqueId(), newTask);
            newTask.plan();
            return;
        }
        if (task.following()) {
            task.startWander(target, timeoutTicks);
        }
    }

    /**
     * 立即停下当前导航。粘性跟随会被暂停几秒，避免「刚停下又被跟随逻辑拉走」。
     */
    public void cancel(Bot bot) {
        if (bot == null) {
            return;
        }
        NavTask task = tasks.remove(bot.getUniqueId());
        if (task != null) {
            task.cancelMovement();
            task.future.complete(false);
        } else {
            bots.stopActions(bot, MoveAction.class);
            bots.stopActions(bot, JumpAction.class);
        }
        FollowState state = follows.get(bot.getUniqueId());
        if (state != null) {
            follows.put(bot.getUniqueId(), state.pause(FOLLOW_PAUSE_MS));
        }
    }

    private void tickAll() {
        for (NavTask task : new ArrayList<>(tasks.values())) {
            try {
                task.tick();
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.WARNING, "假人导航异常", throwable);
                task.fail("导航出错：" + throwable.getMessage());
            }
        }
        resumePausedFollows();
    }

    /**
     * 跟随看门狗：只要还处于跟随状态，任务丢了就重新接上。
     * 这是「让假人一定跟着我」的兜底，任何工具调用打断跟随都能自动恢复。
     */
    private void resumePausedFollows() {
        if (!followDisabled.isEmpty()) {
            // 假人被移除后别把 UUID 一直留在内存里
            followDisabled.removeIf(botId -> {
                Bot bot = bots.byId(botId);
                return bot == null || !bot.isValid();
            });
        }
        if (follows.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, FollowState> entry : new ArrayList<>(follows.entrySet())) {
            UUID botId = entry.getKey();
            FollowState state = entry.getValue();
            if (tasks.containsKey(botId) || locks.containsKey(botId) || state.paused()) {
                continue;
            }
            Bot bot = bots.byId(botId);
            if (bot == null || !bot.isValid()) {
                follows.remove(botId);
                continue;
            }
            if (bots.isDowned(bot) || bot.isDead() || bot.isSleeping()) {
                continue;
            }
            startFollow(bot, state);
        }
    }

    /**
     * 粘性跟随状态。
     */
    private record FollowState(UUID ownerId, double distance, int priority, long pausedUntil) {

        private FollowState pause(long millis) {
            return new FollowState(ownerId, distance, priority, System.currentTimeMillis() + millis);
        }

        private boolean paused() {
            return System.currentTimeMillis() < pausedUntil;
        }
    }

    private final class NavTask {

        private final Bot bot;
        private final Location target;
        private final double tolerance;
        private final int timeoutTicks;
        private final UUID followTarget;
        private final int priority;
        private final CompletableFuture<Boolean> future = new CompletableFuture<>();

        private List<Location> path;
        private int index;
        private int elapsed;
        private int repathCooldown;
        private int jumpCooldown;
        private int progressTimer;
        private int stuckStrikes;
        private double lastX;
        private double lastZ;
        private Location lastGoal;
        private boolean forceRepath;
        private boolean moving;
        private float yaw;
        /** 贴墙时向侧面挪的剩余 tick 与方向 */
        private int sidestepTicks;
        private MoveDirection sidestepDirection;
        /** 完全卡死时先后退，再重新寻路 */
        private int backoffTicks;

        private boolean wandering;
        private Location wanderTarget;
        private int wanderDeadline;

        private NavTask(Bot bot, Location target, double tolerance, int timeoutTicks, UUID followTarget,
                        int priority) {
            this.bot = bot;
            this.target = target;
            this.tolerance = tolerance;
            this.timeoutTicks = timeoutTicks;
            this.followTarget = followTarget;
            this.priority = priority;
            this.lastX = bot.getX();
            this.lastZ = bot.getZ();
            this.yaw = bot.getYaw();
        }

        private boolean following() {
            return followTarget != null;
        }

        private void plan() {
            Location goal = resolveGoal();
            if (goal == null) {
                return;
            }
            replan(goal);
        }

        private Location resolveGoal() {
            if (following()) {
                Player player = Bukkit.getPlayer(followTarget);
                return player == null || !player.isOnline() ? null : player.getLocation();
            }
            return target;
        }

        /**
         * 重新寻路。失败时不会无限等待：跟随会保留任务等待下一次重算，普通任务直接失败。
         */
        private void replan(Location goal) {
            if (goal == null || goal.getWorld() == null) {
                return;
            }
            List<Location> planned = Pathfinder.findPath(bot.getWorld(), bot.getLocation(), goal,
                    settings.navMaxNodes(), settings.navMaxRange());
            lastGoal = goal.clone();
            repathCooldown = following() ? 16 : 30;
            forceRepath = false;
            if (planned.isEmpty()) {
                path = null;
                moving = false;
                cancelMovement();
                if (!following()) {
                    fail("找不到能走过去的路线");
                }
                return;
            }
            path = planned;
            index = 0;
            skipPassedNodes();
        }

        /**
         * 跳过已经走到的路径点；顺带把能直线走到的更远节点提前（视线拉直），
         * 这样拐角处不用沿着格子中心一格一格蹭，视觉上更像玩家在走。
         * 旧实现每次都从 path[0]（自己脚下的格子中心）重新开始，
         * 一旦重新寻路就会掉头走回去，表现为「原地转圈」。
         */
        private void skipPassedNodes() {
            if (path == null) {
                return;
            }
            while (index < path.size() - 1 && reachedNode(path.get(index))) {
                index++;
            }
            int advanced = 0;
            while (index < path.size() - 1 && advanced < 6 && straightWalkable(path.get(index + 1))) {
                index++;
                advanced++;
            }
        }

        private void startWander(Location location, int timeoutTicks) {
            if (wandering) {
                return;
            }
            if (location.getWorld() == null || bot.getWorld() == null
                    || !location.getWorld().equals(bot.getWorld())) {
                return;
            }
            if (following()) {
                // 主人还在走的时候就别闲逛了，先把跟随做好
                Location goal = resolveGoal();
                if (goal == null || goal.getWorld() == null || !goal.getWorld().equals(bot.getWorld())
                        || horizontalDistance(goal) > tolerance + 3.0) {
                    return;
                }
            }
            wandering = true;
            wanderTarget = location.clone();
            wanderDeadline = elapsed + Math.max(40, timeoutTicks);
            path = null;
            index = 0;
            lastGoal = null;
            repathCooldown = 0;
            stuckStrikes = 0;
            progressTimer = 0;
            forceRepath = false;
            moving = true;

            List<Location> planned = Pathfinder.findPath(bot.getWorld(), bot.getLocation(), wanderTarget,
                    settings.navMaxNodes(), settings.navMaxRange());
            lastGoal = wanderTarget.clone();
            repathCooldown = 30;
            if (!planned.isEmpty()) {
                path = planned;
                index = 0;
                skipPassedNodes();
            }
        }

        /**
         * 结束空闲走动，回到跟随逻辑。
         *
         * @param stopMove 是否立即停止移动
         */
        private void stopWander(boolean stopMove) {
            wandering = false;
            wanderTarget = null;
            path = null;
            index = 0;
            lastGoal = null;
            repathCooldown = 40;
            stuckStrikes = 0;
            progressTimer = 0;
            forceRepath = false;
            moving = false;
            if (stopMove) {
                cancelMovement();
            }
        }

        private void tick() {
            if (future.isDone()) {
                tasks.remove(bot.getUniqueId());
                cancelMovement();
                resumeFollow(bot);
                return;
            }
            if (!bot.isValid()) {
                fail("假人已离线");
                return;
            }
            if (bots.isDowned(bot)) {
                cancelMovement();
                return;
            }
            if (bot.isSleeping()) {
                cancelMovement();
                return;
            }

            elapsed += 2;
            if (elapsed > timeoutTicks) {
                fail(following() ? "跟随超时" : "行走超时");
                return;
            }

            Location followGoal = resolveGoal();
            Location goal;

            if (wandering && wanderTarget != null) {
                if (elapsed > wanderDeadline) {
                    stopWander(true);
                    return;
                }
                if (following() && (followGoal == null || followGoal.getWorld() == null
                        || bot.getWorld() == null || !bot.getWorld().equals(followGoal.getWorld())
                        || horizontalDistance(followGoal) > tolerance + 3.0)) {
                    stopWander(true);
                    return;
                }
                goal = wanderTarget;
                if (arrived(goal)) {
                    stopWander(false);
                    cancelMovement();
                    return;
                }
            } else {
                if (followGoal == null || followGoal.getWorld() == null || bot.getWorld() == null
                        || !followGoal.getWorld().equals(bot.getWorld())) {
                    // 主人离线或换了维度：停在原地等，跟随状态保留
                    moving = false;
                    path = null;
                    index = 0;
                    cancelMovement();
                    return;
                }
                goal = followGoal;
                if (following() && shouldIdleFollow(goal)) {
                    return;
                }
                if (!following() && arrived(goal)) {
                    succeed();
                    return;
                }
            }

            if (repathCooldown > 0) {
                repathCooldown -= 2;
            }

            boolean goalMoved = lastGoal == null
                    || lastGoal.distanceSquared(goal) > settings.navRepathDistance() * settings.navRepathDistance();
            boolean needPlan = path == null || goalMoved || forceRepath;
            if (needPlan) {
                if (repathCooldown > 0) {
                    // 冷却中不重算，但有旧路径就继续沿旧路径走（旧实现直接 return，导致走走停停）
                    if (path == null) {
                        moving = false;
                        cancelMovement();
                        return;
                    }
                } else {
                    replan(goal);
                    if (path == null) {
                        return;
                    }
                }
            }

            skipPassedNodes();
            if (index >= path.size() - 1 && reachedNode(path.get(index))) {
                if (following()) {
                    path = null;
                    moving = false;
                } else if (arrived(goal) || closeEnough(goal)) {
                    succeed();
                } else {
                    forceRepath = true;
                    cancelMovement();
                }
                return;
            }

            // 撤退：完全卡死时先后退一小步，再重新寻路（比顶着墙推更像玩家）
            if (backoffTicks > 0) {
                backoffTicks -= 2;
                stopMove(bot, MoveDirection.FORWARD);
                stopMove(bot, MoveDirection.LEFT);
                stopMove(bot, MoveDirection.RIGHT);
                ensureMove(bot, MoveDirection.BACKWARD);
                if (backoffTicks <= 0) {
                    cancelMovement();
                    forceRepath = true;
                }
                return;
            }

            Location node = path.get(index);

            // 转向：限制每次最大转角；偏差过大时先原地转身，不走动
            double dx = node.getX() - bot.getX();
            double dz = node.getZ() - bot.getZ();
            boolean aligned = true;
            if (Math.abs(dx) > 0.08 || Math.abs(dz) > 0.08) {
                float desired = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float delta = wrapDegrees(desired - bot.getYaw());
                float step = Math.max(-MAX_TURN_DEGREES, Math.min(MAX_TURN_DEGREES, delta));
                yaw = wrapDegrees(bot.getYaw() + step);
                bot.setRotation(yaw, 0.0f);
                aligned = Math.abs(delta) <= MOVE_ALIGN_DEGREES;
            }

            if (jumpCooldown > 0) {
                jumpCooldown -= 2;
            }
            if (aligned && node.getY() - bot.getLocation().getY() > 0.55) {
                jump(bot);
            }

            if (sidestepTicks > 0) {
                sidestepTicks -= 2;
                if (sidestepTicks <= 0) {
                    stopSidestep(bot);
                }
            }

            if (aligned) {
                ensureMove(bot, MoveDirection.FORWARD);
                // 视线拉直后仍然正对着一堵墙：能跳就跳，不能跳就侧移贴着墙滑过去。
                // 这是「不容易卡墙」的关键：假人不再一直顶着墙面推。
                Ahead ahead = probeAhead();
                if (ahead == Ahead.STEP_UP) {
                    jump(bot);
                } else if (ahead == Ahead.BLOCKED) {
                    if (!trySidestep(bot)) {
                        // 正面和左右都不通：下一轮立刻重算路线
                        stuckStrikes = Math.max(stuckStrikes, 2);
                        forceRepath = true;
                    }
                }
            } else {
                stopMove(bot, MoveDirection.FORWARD);
                if (sidestepTicks <= 0) {
                    stopSidestep(bot);
                }
            }

            // 卡住检测：每 10 tick 采样一次
            progressTimer += 2;
            if (progressTimer >= 10) {
                progressTimer = 0;
                double moved = Math.hypot(bot.getX() - lastX, bot.getZ() - lastZ);
                if (moved < 0.18) {
                    stuckStrikes++;
                    if (stuckStrikes == 1 || stuckStrikes == 3) {
                        jump(bot);
                    }
                    if (stuckStrikes >= 2) {
                        forceRepath = true;
                    }
                    if (stuckStrikes >= 6 && backoffTicks <= 0) {
                        backoffTicks = BACKOFF_TICKS;
                        beginBackoff();
                        stuckStrikes = 0;
                        return;
                    }
                    if (stuckStrikes >= 8) {
                        recoverStuck(node);
                        stuckStrikes = 0;
                        return;
                    }
                } else {
                    stuckStrikes = 0;
                }
                lastX = bot.getX();
                lastZ = bot.getZ();
            }
        }

        private void beginBackoff() {
            cancelMovement();
            path = null;
            forceRepath = true;
            ensureMove(bot, MoveDirection.BACKWARD);
        }

        /**
         * 跟随滞回：进入 follow-distance 内停下，主人走远 1.5 格以上才重新起步。
         * 避免在边界距离反复起步/停下。
         */
        private boolean shouldIdleFollow(Location goal) {
            double distance = horizontalDistance(goal);
            boolean sameLevel = Math.abs(goal.getY() - bot.getY()) <= 3.0;
            if (moving) {
                if (distance <= tolerance && sameLevel) {
                    moving = false;
                    path = null;
                    index = 0;
                    lastGoal = goal.clone();
                    cancelMovement();
                    return true;
                }
                return false;
            }
            if (distance <= tolerance + FOLLOW_HYSTERESIS && sameLevel) {
                cancelMovement();
                return true;
            }
            moving = true;
            return false;
        }

        private void recoverStuck(Location node) {
            cancelMovement();
            path = null;
            moving = false;
            forceRepath = true;
            if (following()) {
                // 跟随不放弃：停一下重新算路，看门狗会保证继续跟
                repathCooldown = 20;
                return;
            }
            if (settings.teleportFallback() && node != null) {
                bot.teleport(node);
                repathCooldown = 10;
                return;
            }
            fail("被地形挡住了，过不去");
        }

        private void ensureMove(Bot bot, MoveDirection direction) {
            try {
                for (int index = 0; index < bot.getActionSize(); index++) {
                    BotAction<?> action = bot.getAction(index);
                    if (action instanceof MoveAction move && move.getDirection() == direction) {
                        return;
                    }
                }
                MoveAction move = MoveAction.create();
                move.setDirection(direction);
                bot.addAction(move);
            } catch (RuntimeException ignored) {
                // 假人可能已被移除
            }
        }

        private void stopMove(Bot bot, MoveDirection direction) {
            try {
                for (int index = bot.getActionSize() - 1; index >= 0; index--) {
                    BotAction<?> action = bot.getAction(index);
                    if (action instanceof MoveAction move && move.getDirection() == direction) {
                        bot.stopAction(index);
                    }
                }
            } catch (RuntimeException ignored) {
                // 假人可能已被移除
            }
        }

        private void stopSidestep(Bot bot) {
            if (sidestepDirection != null) {
                stopMove(bot, sidestepDirection);
                sidestepDirection = null;
            }
            sidestepTicks = 0;
        }

        /**
         * 正前方被挡住时尝试侧移一步。贴墙滑动比顶着墙推更自然，也能更快绕过门框和墙角。
         */
        private boolean trySidestep(Bot bot) {
            if (sidestepTicks > 0 && sidestepDirection != null) {
                return true;
            }
            boolean left = sideOpen(1);
            boolean right = sideOpen(-1);
            if (!left && !right) {
                return false;
            }
            sidestepDirection = left ? MoveDirection.LEFT : MoveDirection.RIGHT;
            sidestepTicks = SIDESTEP_TICKS;
            ensureMove(bot, sidestepDirection);
            return true;
        }

        /** 探测正前方 0.9 格处的地形。 */
        private Ahead probeAhead() {
            Location origin = bot.getLocation();
            World world = origin.getWorld();
            if (world == null) {
                return Ahead.CLEAR;
            }
            Block feet = blockAtDirection(world, origin, yaw, PROBE_DISTANCE);
            Block head = feet.getRelative(BlockFace.UP);
            Block above = head.getRelative(BlockFace.UP);
            if (isOpen(feet) && isOpen(head)) {
                return Ahead.CLEAR;
            }
            if (feet.getType().isSolid() && isOpen(head) && isOpen(above)) {
                return Ahead.STEP_UP;
            }
            return Ahead.BLOCKED;
        }

        /** 侧面（sign=1 左、-1 右）能否站人。 */
        private boolean sideOpen(int sign) {
            Location origin = bot.getLocation();
            World world = origin.getWorld();
            if (world == null) {
                return false;
            }
            Block feet = blockAtDirection(world, origin, yaw - 90.0f * sign, PROBE_DISTANCE);
            return isOpen(feet) && isOpen(feet.getRelative(BlockFace.UP));
        }

        private Block blockAtDirection(World world, Location origin, float degrees, double distance) {
            double radians = Math.toRadians(degrees);
            double x = origin.getX() - Math.sin(radians) * distance;
            double z = origin.getZ() + Math.cos(radians) * distance;
            // +0.6 是为了跳过脚下那半格（台阶/半砖），取身体下部的方块
            return world.getBlockAt(new Location(world, x, origin.getY() + 0.6, z));
        }

        private boolean isOpen(Block block) {
            Material type = block.getType();
            if (type == Material.LAVA || type == Material.FIRE || type == Material.SOUL_FIRE
                    || type == Material.CACTUS || type == Material.POWDER_SNOW
                    || type == Material.MAGMA_BLOCK) {
                return false;
            }
            return block.isPassable();
        }

        /**
         * 从当前位置到目标点能不能直线走过去（只用于同高度的路径拉直）。
         */
        private boolean straightWalkable(Location node) {
            Location from = bot.getLocation();
            World world = from.getWorld();
            if (world == null || node.getWorld() == null || !world.equals(node.getWorld())) {
                return false;
            }
            if (Math.abs(node.getY() - from.getY()) > 0.75) {
                return false;
            }
            double dx = node.getX() - from.getX();
            double dz = node.getZ() - from.getZ();
            double distance = Math.hypot(dx, dz);
            if (distance < 0.25 || distance > 5.0) {
                return false;
            }
            int steps = Math.max(1, (int) Math.ceil(distance / 0.35));
            for (int i = 1; i <= steps; i++) {
                double t = i / (double) steps;
                Block feet = world.getBlockAt(new Location(world,
                        from.getX() + dx * t, from.getY() + 0.6, from.getZ() + dz * t));
                if (!isOpen(feet) || !isOpen(feet.getRelative(BlockFace.UP))) {
                    return false;
                }
                if (!BlockSupport.supportsFeet(feet.getRelative(BlockFace.DOWN),
                        from.getX() + dx * t, feet.getY(), from.getZ() + dz * t)) {
                    return false;
                }
            }
            return true;
        }

        private void jump(Bot bot) {
            if (jumpCooldown > 0 || !bot.isOnGround()) {
                return;
            }
            JumpAction jump = JumpAction.create();
            jump.setStartDelayTick(0);
            jump.setDoIntervalTick(1);
            jump.setDoNumber(1);
            bot.addAction(jump);
            jumpCooldown = 10;
        }

        private double horizontalDistance(Location goal) {
            double dx = goal.getX() - bot.getX();
            double dz = goal.getZ() - bot.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }

        private boolean reachedNode(Location node) {
            double dx = node.getX() - bot.getX();
            double dz = node.getZ() - bot.getZ();
            return dx * dx + dz * dz < 0.9 * 0.9 && Math.abs(node.getY() - bot.getY()) < 1.3;
        }

        private boolean arrived(Location goal) {
            Location location = bot.getLocation();
            if (location.getWorld() == null || goal.getWorld() == null || !location.getWorld().equals(goal.getWorld())) {
                return false;
            }
            double dx = location.getX() - goal.getX();
            double dz = location.getZ() - goal.getZ();
            double dy = Math.abs(location.getY() - goal.getY());
            return dy <= 2.0 && dx * dx + dz * dz <= tolerance * tolerance;
        }

        /**
         * 路径终点可能停在目标旁边 1~2 格（A* 允许目标点被方块占据），这里放宽一点判定。
         */
        private boolean closeEnough(Location goal) {
            Location location = bot.getLocation();
            if (location.getWorld() == null || goal.getWorld() == null
                    || !location.getWorld().equals(goal.getWorld())) {
                return false;
            }
            double dy = Math.abs(location.getY() - goal.getY());
            return dy <= 2.5 && horizontalDistance(goal) <= Math.max(2.2, tolerance + 1.2);
        }

        private void succeed() {
            tasks.remove(bot.getUniqueId());
            cancelMovement();
            future.complete(true);
            resumeFollow(bot);
        }

        private void fail(String reason) {
            tasks.remove(bot.getUniqueId());
            cancelMovement();
            if (settings.debug()) {
                plugin.getLogger().info("导航失败（" + BotNames.rawName(bot) + "）: " + reason);
            }
            if (!future.isDone()) {
                future.complete(false);
            }
            resumeFollow(bot);
        }

        private void cancelMovement() {
            bots.stopActions(bot, MoveAction.class);
            bots.stopActions(bot, JumpAction.class);
            sidestepTicks = 0;
            sidestepDirection = null;
            try {
                // 导航结束后别一直保持着疾跑标记
                bot.setSprinting(false);
            } catch (RuntimeException ignored) {
                // 假人可能已被移除
            }
        }
    }

    private static float wrapDegrees(float degrees) {
        float value = degrees % 360.0f;
        if (value >= 180.0f) {
            value -= 360.0f;
        }
        if (value < -180.0f) {
            value += 360.0f;
        }
        return value;
    }
}
