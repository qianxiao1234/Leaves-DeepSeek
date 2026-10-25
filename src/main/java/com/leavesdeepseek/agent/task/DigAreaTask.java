package com.leavesdeepseek.agent.task;

import com.google.gson.JsonObject;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code act.digArea}：逐块挖一个立方体区域（体积上限 16³，受保护方块自动跳过）。
 */
public final class DigAreaTask extends AbstractTask {

    private static final int MAX_VOLUME = 16 * 16 * 16;

    private final VecUtil.Point from;
    private final VecUtil.Point to;
    private final Material filter;

    private final List<Block> targets = new ArrayList<>();
    private int index;
    private Material before;
    private boolean breaking;
    private int noProgressTicks;
    private int skipped;

    public DigAreaTask(VecUtil.Point from, VecUtil.Point to, Material filter) {
        this.from = from;
        this.to = to;
        this.filter = filter;
        long volume = (long) (Math.abs(from.x() - to.x()) + 1)
                * (long) (Math.abs(from.y() - to.y()) + 1)
                * (long) (Math.abs(from.z() - to.z()) + 1);
        this.deadline = startedAt + 1000L + Math.min(1_800_000L, volume * 400L);
    }

    @Override
    public String type() {
        return "digArea";
    }

    @Override
    public void start(AIBody body) {
        Bot bot = body.bot();
        World world = body.world();
        if (bot == null || !bot.isValid() || world == null) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳不在线");
            return;
        }
        long volume = (long) (Math.abs(from.x() - to.x()) + 1)
                * (long) (Math.abs(from.y() - to.y()) + 1)
                * (long) (Math.abs(from.z() - to.z()) + 1);
        if (volume > MAX_VOLUME) {
            fail(JsonRpc.OUT_OF_RANGE, "体积 " + volume + " 超过上限 " + MAX_VOLUME);
            return;
        }
        int minX = (int) Math.floor(Math.min(from.x(), to.x()));
        int minY = (int) Math.floor(Math.min(from.y(), to.y()));
        int minZ = (int) Math.floor(Math.min(from.z(), to.z()));
        int maxX = (int) Math.floor(Math.max(from.x(), to.x()));
        int maxY = (int) Math.floor(Math.max(from.y(), to.y()));
        int maxZ = (int) Math.floor(Math.max(from.z(), to.z()));
        Location center = new Location(world, (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        if (center.distance(bot.getLocation()) > body.manager().config().operateRadius() + 8) {
            fail(JsonRpc.OUT_OF_RANGE, "区域超出操作半径");
            return;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir() || block.isLiquid()) {
                        continue;
                    }
                    if (body.manager().config().blockedMaterials().contains(block.getType())) {
                        skipped++;
                        continue;
                    }
                    if (filter != null && block.getType() != filter) {
                        continue;
                    }
                    targets.add(block);
                }
            }
        }
        targets.sort(Comparator.comparingDouble(block -> block.getLocation().distanceSquared(bot.getLocation())));
        if (targets.isEmpty()) {
            finish();
        }
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
            stopBreaking(body, bot);
            fail(JsonRpc.TIMEOUT, "区域挖掘超时（已完成 " + index + "/" + targets.size() + "）");
            return;
        }
        if (index >= targets.size()) {
            stopBreaking(body, bot);
            finish();
            return;
        }
        Block block = targets.get(index);
        if (before == null) {
            before = block.getType();
        }
        if (block.getType() != before || block.getType().isAir()) {
            stopBreaking(body, bot);
            index++;
            before = null;
            noProgressTicks = 0;
            return;
        }
        noProgressTicks++;
        if (noProgressTicks > 160) {
            stopBreaking(body, bot);
            index++;
            before = null;
            noProgressTicks = 0;
            return;
        }

        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        if (bot.getEyeLocation().distance(center) > 4.0) {
            if (!body.manager().plugin().navigator().isNavigating(bot)) {
                body.manager().plugin().navigator().navigate(bot, block.getLocation(), 2.0, 40);
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
        }
    }

    private void stopBreaking(AIBody body, Bot bot) {
        if (breaking) {
            body.manager().plugin().bots().stopActions(bot, BreakBlockAction.class);
            breaking = false;
        }
    }

    @Override
    public Location target() {
        if (index < targets.size()) {
            return targets.get(index).getLocation();
        }
        return null;
    }

    @Override
    public double progress() {
        return targets.isEmpty() ? 1.0 : Math.min(1.0, (double) index / (double) targets.size());
    }

    @Override
    public void cancel() {
        fail(JsonRpc.TIMEOUT, "CANCELLED");
    }

    @Override
    public JsonObject extraData() {
        JsonObject object = new JsonObject();
        object.addProperty("total", targets.size());
        object.addProperty("done", index);
        object.addProperty("skippedBlocked", skipped);
        return object;
    }
}
