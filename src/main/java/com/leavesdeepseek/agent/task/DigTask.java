package com.leavesdeepseek.agent.task;

import com.leavesdeepseek.agent.AIBody;
import com.leavesdeepseek.agent.JsonRpc;
import com.leavesdeepseek.agent.util.VecUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.entity.bot.action.BreakBlockAction;

/**
 * {@code act.dig}：靠近 → 挖掉 → 完成（掉落物由内环自动拾取）。
 */
public final class DigTask extends AbstractTask {

    private final VecUtil.Point point;
    private final boolean collect;

    private Block block;
    private Material before;
    private boolean breaking;
    private int noProgressTicks;

    public DigTask(VecUtil.Point point, boolean collect) {
        this.point = point;
        this.collect = collect;
        this.deadline = startedAt + 30_000L;
    }

    @Override
    public String type() {
        return "dig";
    }

    @Override
    public void start(AIBody body) {
        Bot bot = body.bot();
        World world = body.world();
        if (bot == null || !bot.isValid() || world == null) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳不在线");
            return;
        }
        block = world.getBlockAt((int) Math.floor(point.x()), (int) Math.floor(point.y()),
                (int) Math.floor(point.z()));
        if (block.getType().isAir()) {
            fail(JsonRpc.NOT_FOUND, "那里已经是空气了");
            return;
        }
        if (body.manager().config().blockedMaterials().contains(block.getType())) {
            fail(JsonRpc.DENIED, block.getType().name() + " 属于受保护方块");
            return;
        }
        if (block.getLocation().distance(bot.getLocation()) > body.manager().config().operateRadius()) {
            fail(JsonRpc.OUT_OF_RANGE, "超出操作半径");
            return;
        }
        before = block.getType();
    }

    @Override
    public void tick(AIBody body) {
        if (done() || failed()) {
            return;
        }
        Bot bot = body.bot();
        if (bot == null || !bot.isValid() || block == null) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳已离线");
            return;
        }
        if (block.getType() != before) {
            stopBreaking(body, bot);
            finish();
            return;
        }
        if (timeout()) {
            stopBreaking(body, bot);
            fail(JsonRpc.TIMEOUT, "挖不动 " + fmt(block));
            return;
        }
        noProgressTicks++;

        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        double distance = bot.getEyeLocation().distance(center);
        if (distance > 4.0) {
            if (!body.manager().plugin().navigator().isNavigating(bot)) {
                body.manager().plugin().navigator().navigate(bot, block.getLocation(), 2.0, 60);
            }
            return;
        }
        body.manager().plugin().navigator().cancel(bot);
        Vector direction = center.toVector().subtract(bot.getEyeLocation().toVector());
        Location aim = new Location(bot.getWorld(), 0, 0, 0).setDirection(direction);
        bot.setRotation(aim.getYaw(), aim.getPitch());

        if (!breaking) {
            BreakBlockAction action = BreakBlockAction.create();
            action.setStartDelayTick(1);
            action.setDoIntervalTick(1);
            action.setDoNumber(1);
            bot.addAction(action);
            breaking = true;
            noProgressTicks = 0;
        }
        if (noProgressTicks > 120) {
            stopBreaking(body, bot);
            fail(JsonRpc.BLOCKED, "120 tick 内没有挖掉 " + fmt(block));
        }
    }

    private void stopBreaking(AIBody body, Bot bot) {
        if (breaking) {
            body.manager().plugin().bots().stopActions(bot, BreakBlockAction.class);
            breaking = false;
        }
    }

    private static String fmt(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ();
    }

    @Override
    public Location target() {
        return block == null ? null : block.getLocation();
    }

    @Override
    public void cancel() {
        fail(JsonRpc.TIMEOUT, "CANCELLED");
    }

    @Override
    public com.google.gson.JsonObject extraData() {
        com.google.gson.JsonObject object = new com.google.gson.JsonObject();
        if (block != null) {
            object.addProperty("block", block.getType().name().toLowerCase(java.util.Locale.ROOT));
            object.addProperty("pos", block.getX() + "," + block.getY() + "," + block.getZ());
        }
        object.addProperty("collect", collect);
        return object;
    }
}
