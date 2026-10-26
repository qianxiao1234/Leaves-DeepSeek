package com.leavesdeepseek.agent.task;

import com.leavesdeepseek.agent.AIBody;
import com.leavesdeepseek.agent.JsonRpc;
import com.leavesdeepseek.agent.util.VecUtil;
import com.leavesdeepseek.buddy.bot.BlockSupport;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Openable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.entity.bot.action.UseItemOnAction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** {@code act.interact}: approach a visible side, right-click once, and verify openable blocks. */
public final class InteractTask extends AbstractTask {

    private static final double USE_REACH = 4.5;
    private static final int APPROACH_TIMEOUT_TICKS = 50;

    private final VecUtil.Point point;
    private final Integer hotbarSlot;
    private AIBody owner;
    private Bot bot;
    private Block block;
    private CompletableFuture<Boolean> navigation;
    private List<Location> approachSides;
    private int approachIndex;
    private String initialBlockData;
    private Boolean initialOpen;
    private boolean attempted;
    private int waitTicks;

    public InteractTask(VecUtil.Point point, Integer hotbarSlot) {
        this.point = point;
        this.hotbarSlot = hotbarSlot;
        this.deadline = startedAt + 15_000L;
    }

    @Override
    public String type() {
        return "interact";
    }

    @Override
    public void start(AIBody body) {
        owner = body;
        bot = body.bot();
        World world = body.world();
        if (bot == null || !bot.isValid() || world == null) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳不在线");
            return;
        }
        block = world.getBlockAt((int) Math.floor(point.x()), (int) Math.floor(point.y()),
                (int) Math.floor(point.z()));
        if (block.getType().isAir()) {
            fail(JsonRpc.NOT_FOUND, "目标位置没有可交互的方块");
            return;
        }
        if (block.getBlockData() instanceof Openable
                && block.getBlockData() instanceof Bisected bisected
                && bisected.getHalf() == Bisected.Half.TOP) {
            Block lower = block.getRelative(BlockFace.DOWN);
            if (lower.getType() == block.getType() && lower.getBlockData() instanceof Openable) {
                block = lower;
            }
        }
        if (block.getLocation().distance(bot.getLocation()) > body.manager().config().operateRadius()) {
            fail(JsonRpc.OUT_OF_RANGE, "超出操作半径");
            return;
        }
        initialBlockData = block.getBlockData().getAsString();
        if (block.getBlockData() instanceof Openable openable) {
            initialOpen = openable.isOpen();
        }
        if (hotbarSlot != null) {
            bot.getInventory().setHeldItemSlot(hotbarSlot);
        }
    }

    @Override
    public void tick(AIBody body) {
        if (done() || failed()) {
            return;
        }
        if (bot == null || !bot.isValid() || block == null) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳不在线");
            return;
        }
        if (timeout()) {
            stopNavigation();
            stopInteractionAction();
            fail(JsonRpc.TIMEOUT, "方块交互超时");
            return;
        }
        if (attempted) {
            String currentData = block.getBlockData().getAsString();
            if (initialOpen != null && block.getBlockData() instanceof Openable openable) {
                if (openable.isOpen() != initialOpen) {
                    stopInteractionAction();
                    finish();
                    return;
                }
                if (++waitTicks >= 6) {
                    stopInteractionAction();
                    fail(JsonRpc.BLOCKED, "已右键门，但门的 open 状态没有改变");
                }
                return;
            }
            if (!initialBlockData.equals(currentData) || ++waitTicks >= 3) {
                stopInteractionAction();
                finish();
            }
            return;
        }
        if (block.getType().isAir()) {
            stopNavigation();
            fail(JsonRpc.NOT_FOUND, "目标方块已消失");
            return;
        }

        Location hit = blockCenter(block);
        if (bot.getEyeLocation().distance(hit) > USE_REACH || !canSeeBlock(bot.getEyeLocation(), hit)) {
            approachVisibleSide(body);
            return;
        }

        stopNavigation();
        Vector direction = hit.toVector().subtract(bot.getEyeLocation().toVector());
        Location aim = new Location(bot.getWorld(), 0, 0, 0).setDirection(direction);
        bot.setRotation(aim.getYaw(), aim.getPitch());

        body.manager().plugin().bots().stopActions(bot, UseItemOnAction.class);
        UseItemOnAction action = UseItemOnAction.create();
        action.setStartDelayTick(1);
        action.setDoIntervalTick(1);
        action.setDoNumber(1);
        action.setUseTickTimeout(20);
        bot.addAction(action);
        attempted = true;
    }

    private void approachVisibleSide(AIBody body) {
        if (navigation != null) {
            if (!navigation.isDone()) {
                return;
            }
            boolean arrived;
            try {
                arrived = Boolean.TRUE.equals(navigation.getNow(Boolean.FALSE));
            } catch (RuntimeException exception) {
                arrived = false;
            }
            navigation = null;
            Location hit = blockCenter(block);
            if (!arrived || bot.getEyeLocation().distance(hit) > USE_REACH
                    || !canSeeBlock(bot.getEyeLocation(), hit)) {
                approachIndex++;
            }
        }
        if (approachSides == null) {
            approachSides = visibleStandableSides(bot, block);
        }
        if (approachIndex >= approachSides.size()) {
            stopNavigation();
            fail(JsonRpc.NO_PATH, "找不到能看到目标方块且可站立的位置");
            return;
        }
        Location side = approachSides.get(approachIndex);
        if (!body.manager().plugin().navigator().isNavigating(bot)) {
            navigation = body.manager().plugin().navigator()
                    .navigate(bot, side, 0.75, APPROACH_TIMEOUT_TICKS);
        }
    }

    private static List<Location> visibleStandableSides(Bot bot, Block target) {
        List<Location> sides = new ArrayList<>(4);
        Location hit = blockCenter(target);
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
            Block feet = target.getRelative(face);
            Location side = new Location(target.getWorld(), feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            Block head = feet.getRelative(BlockFace.UP);
            if (!feet.isPassable() || !head.isPassable()
                    || !BlockSupport.supportsAt(target.getWorld(), side.getX(), side.getY(), side.getZ())) {
                continue;
            }
            Location eye = side.clone().add(0.0, 1.62, 0.0);
            if (canSeeBlock(eye, hit)) {
                sides.add(side);
            }
        }
        sides.sort(Comparator.comparingDouble(side -> side.distanceSquared(bot.getLocation())));
        return sides;
    }

    private static Location blockCenter(Block block) {
        BoundingBox bounds = block.getBoundingBox();
        return new Location(block.getWorld(),
                (bounds.getMinX() + bounds.getMaxX()) * 0.5,
                (bounds.getMinY() + bounds.getMaxY()) * 0.5,
                (bounds.getMinZ() + bounds.getMaxZ()) * 0.5);
    }

    private static boolean canSeeBlock(Location eye, Location hit) {
        Vector direction = hit.toVector().subtract(eye.toVector());
        double distance = direction.length();
        if (distance <= 0.0) {
            return true;
        }
        RayTraceResult trace = eye.getWorld().rayTraceBlocks(eye, direction.multiply(1.0 / distance),
                distance + 0.05, FluidCollisionMode.NEVER, true);
        return trace != null && trace.getHitBlock() != null
                && trace.getHitBlock().getLocation().equals(hit.getBlock().getLocation());
    }

    private void stopNavigation() {
        if (owner != null && bot != null && bot.isValid()
                && owner.manager().plugin().navigator().isNavigating(bot)) {
            owner.manager().plugin().navigator().cancel(bot);
        }
        navigation = null;
    }

    private void stopInteractionAction() {
        if (owner != null && bot != null && bot.isValid()) {
            owner.manager().plugin().bots().stopActions(bot, UseItemOnAction.class);
        }
    }

    @Override
    public Location target() {
        return block == null ? null : block.getLocation();
    }

    @Override
    public void cancel() {
        stopNavigation();
        stopInteractionAction();
        fail(JsonRpc.TIMEOUT, "CANCELLED");
    }
}
