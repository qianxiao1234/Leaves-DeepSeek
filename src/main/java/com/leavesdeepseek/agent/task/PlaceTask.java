package com.leavesdeepseek.agent.task;

import com.leavesdeepseek.agent.AIBody;
import com.leavesdeepseek.agent.JsonRpc;
import com.leavesdeepseek.agent.util.ItemUtil;
import com.leavesdeepseek.agent.util.VecUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.entity.bot.action.UseItemOnAction;

/**
 * {@code act.place}：手上有对应方块 → 对准 → 右键放置。
 */
public final class PlaceTask extends AbstractTask {

    private final VecUtil.Point point;
    private final Material material;

    private Block block;
    private boolean placed;
    private int waited;

    public PlaceTask(VecUtil.Point point, Material material) {
        this.point = point;
        this.material = material;
        this.deadline = startedAt + 20_000L;
    }

    @Override
    public String type() {
        return "place";
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
        if (!block.getType().isAir() && !block.isLiquid()) {
            fail(JsonRpc.BLOCKED, "目标位置已被 " + block.getType().name() + " 占用");
            return;
        }
        if (body.manager().config().blockedMaterials().contains(material)) {
            fail(JsonRpc.DENIED, material.name() + " 属于受保护方块");
            return;
        }
        if (block.getLocation().distance(bot.getLocation()) > body.manager().config().operateRadius()) {
            fail(JsonRpc.OUT_OF_RANGE, "超出操作半径");
            return;
        }
        if (ItemUtil.findSlot(bot.getInventory(), material) < 0) {
            fail(JsonRpc.NOT_HOLDING, "背包里没有 " + material.name());
            return;
        }
        ItemUtil.equip(bot, material);
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
        if (block.getType() == material) {
            finish();
            return;
        }
        if (timeout()) {
            fail(JsonRpc.TIMEOUT, "方块没有放上去");
            return;
        }
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        if (bot.getEyeLocation().distance(center) > 4.0) {
            if (!body.manager().plugin().navigator().isNavigating(bot)) {
                body.manager().plugin().navigator().navigate(bot, block.getLocation(), 2.5, 60);
            }
            return;
        }
        body.manager().plugin().navigator().cancel(bot);
        Vector direction = center.toVector().subtract(bot.getEyeLocation().toVector());
        Location aim = new Location(bot.getWorld(), 0, 0, 0).setDirection(direction);
        bot.setRotation(aim.getYaw(), aim.getPitch());
        if (placed) {
            waited++;
            return;
        }
        if (ItemUtil.findSlot(bot.getInventory(), material) < 0) {
            fail(JsonRpc.NOT_HOLDING, "手上的 " + material.name() + " 用完了");
            return;
        }
        UseItemOnAction action = UseItemOnAction.create();
        action.setStartDelayTick(2);
        action.setDoIntervalTick(2);
        action.setDoNumber(1);
        action.setUseTickTimeout(20);
        bot.addAction(action);
        placed = true;
    }

    @Override
    public Location target() {
        return block == null ? null : block.getLocation();
    }

    @Override
    public void cancel() {
        fail(JsonRpc.TIMEOUT, "CANCELLED");
    }
}
