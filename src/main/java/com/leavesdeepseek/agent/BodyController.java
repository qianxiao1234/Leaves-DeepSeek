package com.leavesdeepseek.agent;

import com.leavesdeepseek.agent.task.AgentTask;
import com.leavesdeepseek.buddy.bot.WeaponRules;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.entity.bot.action.UseItemAction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 内环：20 Hz 把「意图」翻译成「这一 tick 的具体动作」（规格 §2.1）。
 *
 * <p>只把异常经 {@link SensorHub} 上报给外环，正常的转向/绕行/冷却/捡物/进食都在这里消化。</p>
 */
public final class BodyController {

    /** 提交任务的结果。 */
    public record Submitted(String taskId, String status, String type, long etaMs) {
    }

    private final AIBody body;
    private final Deque<AgentTask> queue = new ArrayDeque<>();

    private AgentTask current;
    private int sequence;
    private int ticks;

    private int eatingTicks;
    private int eatSlot = -1;
    private int eatFoodBefore;
    private int pickupCooldown;
    private int toolCheck;
    private int lastToolWarnTick;
    private int stuckTicks;
    private int stuckPushes;
    private int sideSign = 1;
    private org.bukkit.Location lastPos;

    /** 同一任务最多侧推几次（超过判 NO_PROGRESS）。 */
    private static final int MAX_STUCK_PUSHES = 3;

    public BodyController(AIBody body) {
        this.body = body;
    }

    public AgentTask current() {
        return current;
    }

    public List<AgentTask> snapshot() {
        List<AgentTask> list = new ArrayList<>();
        if (current != null) {
            list.add(current);
        }
        list.addAll(queue);
        return list;
    }

    public String nextId() {
        return "t-" + (++sequence);
    }

    /**
     * 提交任务。
     *
     * @param interrupt true 表示打断当前任务并清空队列
     */
    public Submitted submit(AgentTask task, boolean interrupt) {
        task.id(nextId());
        if (interrupt) {
            clear("CANCELLED");
        }
        int limit = body.manager().config().maxConcurrentTasks();
        int running = (current == null ? 0 : 1) + queue.size();
        if (running >= limit) {
            throw new JsonRpc.RpcException(JsonRpc.BUSY,
                    "并发任务已达上限 " + limit,
                    JsonRpc.object("queued", queue.size(), "currentTask", current == null ? null : current.id()));
        }
        if (current == null) {
            startTask(task);
        } else {
            queue.addLast(task);
            body.sensors().event("task.started", JsonRpc.object("taskId", task.id(), "type", task.type(),
                    "queued", true));
        }
        return new Submitted(task.id(), "started", task.type(),
                Math.max(0L, task.etaMs()));
    }

    private void startTask(AgentTask task) {
        current = task;
        stuckPushes = 0;
        stuckTicks = 0;
        lastPos = null;
        try {
            task.start(body);
        } catch (Throwable throwable) {
            task.fail(JsonRpc.INTERNAL, throwable.getMessage());
            body.manager().plugin().getLogger().warning("躯壳任务启动失败：" + throwable.getMessage());
        }
        body.sensors().taskStarted(task);
        if (task.done() || task.failed()) {
            finishCurrent();
        }
    }

    /** 取消任务；{@code taskId == null} 且 {@code all} 时清空全部。 */
    public int cancel(String taskId, boolean all) {
        int cancelled = 0;
        if (all || taskId == null) {
            cancelled = clear("CANCELLED");
            return cancelled;
        }
        if (current != null && taskId.equals(current.id())) {
            current.cancel();
            body.sensors().taskFailed(current, "CANCELLED");
            finishCurrent();
            return 1;
        }
        for (AgentTask task : new ArrayList<>(queue)) {
            if (taskId.equals(task.id())) {
                queue.remove(task);
                body.sensors().taskFailed(task, "CANCELLED");
                cancelled++;
            }
        }
        return cancelled;
    }

    private int clear(String reason) {
        int cancelled = 0;
        Bot bot = body.bot();
        if (bot != null && bot.isValid()) {
            // 任何「换任务/取消」都要断掉粘性跟随，不然 follow 任务结束后它还会自己跟着跑
            body.manager().plugin().navigator().abortFollow(bot);
        }
        if (current != null) {
            try {
                current.cancel();
            } catch (Throwable ignored) {
                // 取消失败不阻塞清理
            }
            body.sensors().taskFailed(current, reason);
            current = null;
            cancelled++;
        }
        for (AgentTask task : queue) {
            body.sensors().taskFailed(task, reason);
            cancelled++;
        }
        queue.clear();
        return cancelled;
    }

    /** 状态切换 / 下线时清空（规格 §16.6）。 */
    public void stopAll(String reason) {
        clear(reason);
        Bot bot = body.bot();
        if (bot != null && bot.isValid()) {
            // 躯壳的跟随只是任务：必须连「粘性跟随状态」一起清，否则看门狗会把它接回去
            body.manager().plugin().navigator().abortFollow(bot);
            body.manager().plugin().navigator().cancel(bot);
            body.manager().plugin().bots().stopAllActions(bot);
        }
    }

    private void finishCurrent() {
        AgentTask task = current;
        if (task == null) {
            return;
        }
        current = null;
        if (task.failed()) {
            body.sensors().taskFailed(task, task.failDetail());
        } else if (task.done()) {
            body.sensors().taskDone(task);
        }
        if (!queue.isEmpty() && body.state() == AIBody.State.REMOTE) {
            startTask(queue.pollFirst());
        }
    }

    /** 20 Hz 主循环（主线程）。 */
    public void tick() {
        ticks++;
        if (body.state() != AIBody.State.REMOTE) {
            return;
        }
        Bot bot = body.bot();
        if (bot == null || !bot.isValid() || bot.isDead()) {
            return;
        }
        tickEating(bot);
        // 内环必须自己活着：饿了自己吃、工具快碎了换备、碎之前报告（与有没有任务无关）
        innerMaintenance(bot);

        if (current != null) {
            trackStuck(bot);
            try {
                current.tick(body);
            } catch (Throwable throwable) {
                current.fail(JsonRpc.INTERNAL, throwable.getMessage());
                body.manager().plugin().getLogger().warning("躯壳任务异常：" + throwable.getMessage());
            }
            if (ticks % 4 == 0) {
                body.sensors().taskProgress(current);
            }
            if (current.done() || current.failed()) {
                finishCurrent();
            }
            return;
        }
        if (!queue.isEmpty()) {
            startTask(queue.pollFirst());
            return;
        }
        idlePickup(bot);
    }

    /**
     * 内环维护（规格 §2.1）：自动进食 + 工具换备 + 耐久告警。
     * <p>这些**不能**只放在「空闲」分支里，否则 AI 一直下任务时躯壳会饿死。</p>
     */
    private void innerMaintenance(Bot bot) {
        // 1) 工具耐久：<10% 先报 ev.toolLow，再尝试换同类备用
        if (++toolCheck >= 20) {
            toolCheck = 0;
            ItemStack held = bot.getInventory().getItemInMainHand();
            if (held != null && !held.getType().isAir() && WeaponRules.isNearlyBroken(held)) {
                if (ticks - lastToolWarnTick >= 100) {
                    lastToolWarnTick = ticks;
                    int remaining = com.leavesdeepseek.agent.util.ItemUtil.durability(held);
                    body.sensors().event("ev.toolLow", JsonRpc.object(
                            "slot", bot.getInventory().getHeldItemSlot(),
                            "item", com.leavesdeepseek.agent.util.ItemUtil.nameOf(held),
                            "remaining", remaining));
                }
                int bestSlot = WeaponRules.bestSlot(bot.getInventory());
                if (bestSlot >= 0) {
                    ItemStack better = bot.getInventory().getItem(bestSlot);
                    if (better != null && !WeaponRules.isNearlyBroken(better)) {
                        com.leavesdeepseek.agent.util.ItemUtil.equip(bot, better.getType());
                    }
                }
            }
        }
        // 2) 自动进食（挖/放/打的时候不抢手，其余情况立刻吃）
        if (eatingTicks > 0) {
            return;
        }
        if (bot.getFoodLevel() >= body.manager().config().autoEatThreshold()) {
            return;
        }
        if (current != null) {
            String type = current.type();
            if ("dig".equals(type) || "digArea".equals(type) || "place".equals(type)
                    || "attack".equals(type)) {
                return;
            }
        }
        int slot = com.leavesdeepseek.agent.util.ItemUtil.bestFoodSlot(bot.getInventory(), false, null);
        if (slot >= 0) {
            startEating(bot, slot);
        }
    }

    private void idlePickup(Bot bot) {
        if (body.manager().plugin().navigator().isNavigating(bot) || pickupCooldown > 0) {
            if (pickupCooldown > 0) {
                pickupCooldown--;
            }
            return;
        }
        Item nearest = null;
        double best = 9.0;
        for (Entity entity : bot.getNearbyEntities(3, 3, 3)) {
            if (!(entity instanceof Item item) || !item.isValid()) {
                continue;
            }
            double distance = item.getLocation().distanceSquared(bot.getLocation());
            if (distance < best) {
                best = distance;
                nearest = item;
            }
        }
        if (nearest != null) {
            pickupCooldown = 20;
            body.manager().plugin().navigator().navigate(bot, nearest.getLocation(), 0.8, 40);
        }
    }

    /**
     * 卡住自愈（P0-3）：连续 10 tick 位移 &lt; 0.05 格 → 侧推。
     *
     * <p>硬性约束（交付文档 §P0-3）：</p>
     * <ul>
     *   <li>只用 {@code setVelocity(±0.22 水平, 0.10 垂直)}，**禁止 setPosition**（会被服务端判定非法位置拉回）；</li>
     *   <li>方向选两侧净空更大的一边；两侧都堵 → 只向上跳；</li>
     *   <li>落点下方必须有支撑；</li>
     *   <li>同一任务最多推 3 次，超了直接 {@code NO_PROGRESS} 失败；</li>
     *   <li>没有正在执行导航时不算卡住（站着等冷却/等目标都是合法的）。</li>
     * </ul>
     */
    private void trackStuck(Bot bot) {
        Location now = bot.getLocation();
        if (lastPos == null || lastPos.getWorld() != now.getWorld()) {
            lastPos = now.clone();
            stuckTicks = 0;
            return;
        }
        if (now.distanceSquared(lastPos) > 0.0025) {
            lastPos = now.clone();
            stuckTicks = 0;
            return;
        }
        if (++stuckTicks < 10) {
            return;
        }
        stuckTicks = 0;
        lastPos = now.clone();
        if (!body.manager().plugin().navigator().isNavigating(bot)) {
            // 没有导航任务时「不动」是正常的（等攻击冷却、look、待命）
            return;
        }
        if (stuckPushes >= MAX_STUCK_PUSHES) {
            if (current != null && !current.failed()) {
                body.manager().plugin().log().info("躯壳",
                        "[TRACK-STUCK] task=" + current.id() + " 推了 " + stuckPushes + " 次仍无进展，判 NO_PROGRESS");
                current.fail(JsonRpc.TIMEOUT, "NO_PROGRESS");
            }
            return;
        }

        Location before = now.clone();
        int leftSpace = sideSpace(now, -1);
        int rightSpace = sideSpace(now, 1);
        Vector push;
        String dir;
        if (leftSpace <= 0 && rightSpace <= 0) {
            push = new Vector(0, 0.42, 0);
            dir = "UP";
        } else {
            sideSign = leftSpace >= rightSpace ? -1 : 1;
            double angle = Math.toRadians(now.getYaw() + 90.0 * sideSign);
            double vx = -Math.sin(angle) * 0.22;
            double vz = Math.cos(angle) * 0.22;
            Location landing = new Location(now.getWorld(),
                    now.getX() + vx * 6, now.getY(), now.getZ() + vz * 6);
            org.bukkit.block.Block below = landing.clone().subtract(0, 1, 0).getBlock();
            if (below.isPassable() && !below.isLiquid()) {
                // 落点悬空：只跳不侧移
                push = new Vector(0, 0.42, 0);
                dir = "UP";
            } else {
                push = new Vector(vx, Math.max(0.10, bot.getVelocity().getY()), vz);
                dir = sideSign > 0 ? "RIGHT" : "LEFT";
            }
        }
        bot.setVelocity(push);
        stuckPushes++;
        if (current != null) {
            current.stuck(current.stuck() + 1);
        }
        final String logDir = dir;
        final int lSpace = leftSpace;
        final int rSpace = rightSpace;
        final int attempt = stuckPushes;
        body.manager().plugin().log().info("躯壳",
                "[TRACK-STUCK] task=" + (current == null ? "-" : current.id())
                        + " tick=" + ticks + " why=no_progress"
                        + " pos(before)=" + fmt(before)
                        + " push(vel)=" + fmtVec(push) + " dir=" + logDir
                        + " space(w/e)=" + lSpace + "/" + rSpace
                        + " attempt=" + attempt + "/" + MAX_STUCK_PUSHES);
        // 下一 tick 的位置就是「有没有被拉回来」的证据
        Bot target = bot;
        String taskId = current == null ? null : current.id();
        Bukkit.getScheduler().runTask(body.manager().plugin(), () -> {
            if (target.isValid()) {
                body.manager().plugin().log().info("躯壳",
                        "[TRACK-STUCK] task=" + (taskId == null ? "-" : taskId)
                                + " pos(next_tick)=" + fmt(target.getLocation()));
            }
        });
        body.sensors().event("ev.stuck", JsonRpc.object(
                "taskId", current == null ? "" : current.id(),
                "pos", com.leavesdeepseek.agent.util.VecUtil.toArray(now),
                "blockedBy", blockedAround(now)));
    }

    /** 某一侧（相对朝向）能走几格，最多算 3 格。 */
    private int sideSpace(Location from, int side) {
        org.bukkit.World world = from.getWorld();
        if (world == null) {
            return 0;
        }
        double angle = Math.toRadians(from.getYaw() + 90.0 * side);
        int dx = (int) Math.round(-Math.sin(angle));
        int dz = (int) Math.round(Math.cos(angle));
        int space = 0;
        for (int step = 1; step <= 3; step++) {
            org.bukkit.block.Block feet = world.getBlockAt(from.getBlockX() + dx * step,
                    from.getBlockY(), from.getBlockZ() + dz * step);
            org.bukkit.block.Block head = feet.getRelative(org.bukkit.block.BlockFace.UP);
            org.bukkit.block.Block below = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
            if (!feet.isPassable() || !head.isPassable()) {
                break;
            }
            if (below.isPassable() && !below.isLiquid()) {
                break;
            }
            space++;
        }
        return space;
    }

    private static String fmt(Location location) {
        return Math.round(location.getX() * 100) / 100.0 + ","
                + Math.round(location.getY() * 100) / 100.0 + ","
                + Math.round(location.getZ() * 100) / 100.0;
    }

    private static String fmtVec(Vector vector) {
        return "(" + Math.round(vector.getX() * 100) / 100.0 + ","
                + Math.round(vector.getY() * 100) / 100.0 + ","
                + Math.round(vector.getZ() * 100) / 100.0 + ")";
    }

    private com.google.gson.JsonArray blockedAround(Location center) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        if (center.getWorld() == null) {
            return array;
        }
        for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[]{
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            org.bukkit.block.Block block = center.getBlock().getRelative(face);
            if (block.isPassable()) {
                continue;
            }
            array.add(block.getType().name().toLowerCase(java.util.Locale.ROOT)
                    + "@" + block.getX() + "," + block.getY() + "," + block.getZ());
        }
        return array;
    }

    /**
     * 开始吃某个槽位的食物（会先切到主手）。
     *
     * @return 实际吃的东西；{@code null} 表示这个槽位不能吃
     */
    public ItemStack startEating(Bot bot, int slot) {
        ItemStack stack = bot.getInventory().getItem(slot);
        if (stack == null || !stack.getType().isEdible() || bot.getFoodLevel() >= 20) {
            return null;
        }
        com.leavesdeepseek.agent.util.ItemUtil.equip(bot, stack.getType());
        eatSlot = slot;
        eatFoodBefore = bot.getFoodLevel();
        eatingTicks = 60;
        body.manager().plugin().bots().stopActions(bot, UseItemAction.class);
        UseItemAction action = UseItemAction.create();
        action.setStartDelayTick(1);
        action.setDoIntervalTick(1);
        action.setDoNumber(1);
        action.setUseTickTimeout(40);
        bot.addAction(action);
        return stack;
    }

    private void tickEating(Bot bot) {
        if (eatingTicks <= 0) {
            return;
        }
        eatingTicks--;
        if (bot.getFoodLevel() > eatFoodBefore || eatingTicks <= 0) {
            body.manager().plugin().bots().stopActions(bot, UseItemAction.class);
            eatingTicks = 0;
            eatSlot = -1;
            if (bot.getFoodLevel() < body.manager().config().autoEatThreshold()) {
                body.sensors().event("ev.hungry", JsonRpc.object("food", bot.getFoodLevel(),
                        "saturation", Math.round(bot.getSaturation() * 10) / 10.0));
            }
        }
    }

    public boolean eating() {
        return eatingTicks > 0;
    }

    /** 当前正在跑的任务位置（K 帧用）。 */
    public Location currentTarget() {
        return current == null ? null : current.target();
    }
}
