package com.leavesdeepseek.agent.task;

import com.leavesdeepseek.agent.AIBody;
import com.leavesdeepseek.agent.JsonRpc;
import com.leavesdeepseek.agent.util.VecUtil;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.Map;

/**
 * {@code act.container}：箱子/桶存取。默认被 {@code body.allow-container: false} 禁用。
 */
public final class ContainerTask extends AbstractTask {

    private final VecUtil.Point point;
    private final String action;
    private final Material material;
    private final int count;

    public ContainerTask(VecUtil.Point point, String action, Material material, int count) {
        this.point = point;
        this.action = action == null ? "open" : action.toLowerCase(java.util.Locale.ROOT);
        this.material = material;
        this.count = Math.max(1, Math.min(64 * 9, count));
        this.deadline = startedAt + 10_000L;
    }

    @Override
    public String type() {
        return "container";
    }

    @Override
    public void start(AIBody body) {
        if (!body.configBoolean("allow-container", body.manager().config().allowContainer())) {
            fail(JsonRpc.DENIED, "服务器禁用了容器操作（body.allow-container: false）");
            return;
        }
        Bot bot = body.bot();
        World world = body.world();
        if (bot == null || !bot.isValid() || world == null) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳不在线");
            return;
        }
        Block block = world.getBlockAt((int) Math.floor(point.x()), (int) Math.floor(point.y()),
                (int) Math.floor(point.z()));
        if (!(block.getState() instanceof Container container)) {
            fail(JsonRpc.NOT_FOUND, "那里不是容器");
            return;
        }
        if (block.getLocation().distance(bot.getLocation()) > 6.0) {
            fail(JsonRpc.OUT_OF_RANGE, "离容器太远（>6 格）");
            return;
        }
        Inventory chest = container.getInventory();
        switch (action) {
            case "open" -> finish();
            case "withdraw" -> {
                int moved = withdraw(chest, bot.getInventory(), count);
                if (moved <= 0) {
                    fail(JsonRpc.NOT_FOUND, "容器里没有可取出的物品");
                } else {
                    finish();
                }
            }
            case "deposit" -> {
                if (material == null) {
                    fail(JsonRpc.BAD_REQUEST, "deposit 需要指定 item");
                    return;
                }
                int moved = deposit(bot.getInventory(), chest, count);
                if (moved <= 0) {
                    fail(JsonRpc.NOT_HOLDING, "背包里没有 " + material.name());
                } else {
                    finish();
                }
            }
            default -> fail(JsonRpc.BAD_REQUEST, "未知的容器动作：" + action);
        }
    }

    private int withdraw(Inventory chest, Inventory inventory, int limit) {
        int moved = 0;
        for (int slot = 0; slot < chest.getSize() && moved < limit; slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            if (material != null && stack.getType() != material) {
                continue;
            }
            int take = Math.min(limit - moved, stack.getAmount());
            ItemStack copy = stack.clone();
            copy.setAmount(take);
            Map<Integer, ItemStack> leftover = inventory.addItem(copy);
            int actuallyMoved = take;
            for (ItemStack rest : leftover.values()) {
                actuallyMoved -= rest.getAmount();
            }
            if (actuallyMoved <= 0) {
                return moved;
            }
            moved += actuallyMoved;
            int remain = stack.getAmount() - actuallyMoved;
            if (remain <= 0) {
                chest.setItem(slot, null);
            } else {
                ItemStack keep = stack.clone();
                keep.setAmount(remain);
                chest.setItem(slot, keep);
            }
        }
        return moved;
    }

    private int deposit(Inventory inventory, Inventory chest, int limit) {
        int moved = 0;
        for (int slot = 0; slot < inventory.getSize() && moved < limit; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            if (material != null && stack.getType() != material) {
                continue;
            }
            int take = Math.min(limit - moved, stack.getAmount());
            ItemStack copy = stack.clone();
            copy.setAmount(take);
            Map<Integer, ItemStack> leftover = chest.addItem(copy);
            int actuallyMoved = take;
            for (ItemStack rest : leftover.values()) {
                actuallyMoved -= rest.getAmount();
            }
            if (actuallyMoved <= 0) {
                return moved;
            }
            moved += actuallyMoved;
            int remain = stack.getAmount() - actuallyMoved;
            if (remain <= 0) {
                inventory.setItem(slot, null);
            } else {
                ItemStack keep = stack.clone();
                keep.setAmount(remain);
                inventory.setItem(slot, keep);
            }
        }
        return moved;
    }

    @Override
    public void tick(AIBody body) {
        if (!done() && !failed()) {
            finish();
        }
    }

    @Override
    public void cancel() {
        fail(JsonRpc.TIMEOUT, "CANCELLED");
    }
}
