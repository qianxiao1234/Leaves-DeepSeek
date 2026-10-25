package com.leavesdeepseek.agent.task;

import com.leavesdeepseek.agent.AIBody;
import com.leavesdeepseek.agent.JsonRpc;
import org.bukkit.Location;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.concurrent.CompletableFuture;

/**
 * {@code act.goto}：复用现有 {@code BotNavigator} 的 A* 寻路。
 */
public final class GotoTask extends AbstractTask {

    private final Location target;
    private final double range;
    private final boolean sprint;

    private CompletableFuture<Boolean> future;
    private Bot bot;

    public GotoTask(Location target, double range, boolean sprint) {
        this.target = target;
        this.range = Math.max(0.6, range);
        this.sprint = sprint;
        this.deadline = startedAt + 45_000L;
    }

    @Override
    public String type() {
        return "goto";
    }

    @Override
    public void start(AIBody body) {
        Bot current = body.bot();
        if (current == null || !current.isValid()) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳不在线");
            return;
        }
        this.bot = current;
        if (sprint) {
            current.setSprinting(true);
        }
        body.manager().plugin().log().info("躯壳",
                "[TASK] " + id() + " goto created    target=" + (target == null ? "?" : target.getBlockX()
                        + "," + target.getBlockY() + "," + target.getBlockZ()));
        future = body.manager().plugin().navigator().navigateAndWait(current, target, range, 900);
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
            fail(JsonRpc.TIMEOUT, "goto 超时");
            return;
        }
        if (future == null) {
            fail(JsonRpc.INTERNAL, "导航未启动");
            return;
        }
        if (!future.isDone()) {
            return;
        }
        boolean arrived = false;
        try {
            arrived = Boolean.TRUE.equals(future.getNow(Boolean.FALSE));
        } catch (RuntimeException ignored) {
            arrived = false;
        }
        Location here = bot.getLocation();
        double distance = here.distance(target);
        if (arrived || distance <= range + 0.6) {
            if (sprint) {
                bot.setSprinting(false);
            }
            body.manager().plugin().log().info("躯壳",
                    "[TASK] " + id() + " goto done       elapsed=" + elapsedMs()
                            + "ms reason=distance_ok");
            finish();
            return;
        }
        body.manager().plugin().log().info("躯壳",
                "[TASK] " + id() + " goto fail       elapsed=" + elapsedMs() + "ms reason=NO_PATH");
        fail(JsonRpc.NO_PATH, "到不了 " + fmt(target) + "（当前距离 " + Math.round(distance * 10) / 10.0 + "）");
    }

    @Override
    public Location target() {
        return target;
    }

    @Override
    public long etaMs() {
        Bot current = this.bot;
        if (done() || failed()) {
            return 0L;
        }
        if (current == null || !current.isValid() || current.getWorld() == null
                || !current.getWorld().equals(target.getWorld())) {
            return -1L;
        }
        double distance = current.getLocation().distance(target);
        // 疾跑约 5.6 格/秒，保守取 4 格/秒
        return Math.max(200L, (long) (distance / 4.0 * 1000.0));
    }

    @Override
    public double progress() {
        if (done()) {
            return 1.0;
        }
        return super.progress();
    }

    @Override
    public void cancel() {
        fail(JsonRpc.TIMEOUT, "CANCELLED");
    }

    static String fmt(Location location) {
        if (location == null) {
            return "?";
        }
        return Math.round(location.getX() * 10) / 10.0 + "," + Math.round(location.getY() * 10) / 10.0
                + "," + Math.round(location.getZ() * 10) / 10.0;
    }
}
