package com.leavesdeepseek.agent.task;

import com.google.gson.JsonObject;
import com.leavesdeepseek.agent.AIBody;
import com.leavesdeepseek.agent.JsonRpc;
import com.leavesdeepseek.agent.util.VecUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.leavesmc.leaves.entity.bot.Bot;

/**
 * {@code act.follow}：跟随某个玩家，**进入距离并稳定 10 tick 就算完成**。
 *
 * <p>旧实现只调一次 {@code navigator.follow()} 然后永远不结束：假人站在 2.4 格时导航认为「到了」、
 * 任务却还在跑，配合卡住自愈就变成「左右各 0.47 格来回抖、任务永不完结」（交付文档 P0-2/P0-3）。
 * 现在：</p>
 * <ul>
 *   <li>到达判定带滞回：{@code d <= distance + 0.5} 连续 10 tick → {@code task.done}；</li>
 *   <li>无进展判据：20 tick 内净位移 &lt; 0.5 格且仍未进入距离 → {@code task.failed(NO_PROGRESS)}；</li>
 *   <li>目标点由 {@code BotNavigator} 自己节流重算，任务层不再每 tick 改目标。</li>
 * </ul>
 */
public final class FollowTask extends AbstractTask {

    private static final int ARRIVE_TICKS = 10;
    private static final int NO_PROGRESS_TICKS = 20;

    private final String playerName;
    private final double distance;

    private AIBody owner;
    private int inRangeTicks;
    private int progressTicks;
    private Location anchor;

    public FollowTask(String playerName, double distance) {
        this.playerName = playerName;
        this.distance = Math.max(1.0, distance);
        this.deadline = Long.MAX_VALUE;
    }

    @Override
    public String type() {
        return "follow";
    }

    @Override
    public void start(AIBody body) {
        this.owner = body;
        Bot bot = body.bot();
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null || !player.isOnline()) {
            fail(JsonRpc.NOT_FOUND, "找不到玩家 " + playerName);
            return;
        }
        if (bot == null || !bot.isValid()) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳不在线");
            return;
        }
        double current = VecUtil.horizontal(bot.getLocation(), player.getLocation());
        body.manager().plugin().log().info("躯壳",
                "[TASK] " + id() + " follow created  target=" + playerName
                        + " d=" + VecUtil.round(current) + " threshold=" + distance);
        body.manager().plugin().navigator().follow(bot, player, distance);
        anchor = bot.getLocation().clone();
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
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null || !player.isOnline()) {
            fail(JsonRpc.NOT_FOUND, "玩家 " + playerName + " 已离线");
            return;
        }
        if (bot.getWorld() == null || !bot.getWorld().equals(player.getWorld())) {
            fail(JsonRpc.NOT_FOUND, "玩家 " + playerName + " 去了别的世界");
            return;
        }

        double current = VecUtil.horizontal(bot.getLocation(), player.getLocation());
        if (current <= distance + 0.5) {
            if (++inRangeTicks >= ARRIVE_TICKS) {
                body.manager().plugin().log().info("躯壳",
                        "[TASK] " + id() + " follow done     elapsed=" + elapsedMs()
                                + "ms reason=distance_ok d=" + VecUtil.round(current));
                finish();
            }
            return;
        }
        inRangeTicks = 0;

        if (++progressTicks < NO_PROGRESS_TICKS) {
            return;
        }
        progressTicks = 0;
        Location now = bot.getLocation();
        if (anchor != null && now.distanceSquared(anchor) < 0.25) {
            body.manager().plugin().log().info("躯壳",
                    "[TASK] " + id() + " follow fail     elapsed=" + elapsedMs()
                            + "ms reason=NO_PROGRESS d=" + VecUtil.round(current));
            fail(JsonRpc.TIMEOUT, "NO_PROGRESS");
            return;
        }
        anchor = now.clone();
    }

    @Override
    public double progress() {
        if (done()) {
            return 1.0;
        }
        if (owner == null) {
            return 0.0;
        }
        Bot bot = owner.bot();
        Player player = Bukkit.getPlayerExact(playerName);
        if (bot == null || !bot.isValid() || player == null) {
            return 0.0;
        }
        double current = VecUtil.horizontal(bot.getLocation(), player.getLocation());
        if (current <= distance + 0.5) {
            return 0.95;
        }
        return Math.max(0.0, Math.min(0.9, distance / current));
    }

    @Override
    public long etaMs() {
        return -1L;
    }

    @Override
    public Location target() {
        Player player = Bukkit.getPlayerExact(playerName);
        return player == null ? null : player.getLocation();
    }

    @Override
    public JsonObject extraData() {
        JsonObject object = new JsonObject();
        object.addProperty("reason", failed() ? "no_progress" : "distance_ok");
        object.addProperty("target", playerName);
        return object;
    }

    @Override
    public void cancel() {
        fail(JsonRpc.TIMEOUT, "CANCELLED");
    }
}
