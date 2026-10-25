package com.leavesdeepseek.agent.task;

import com.leavesdeepseek.agent.AIBody;
import com.leavesdeepseek.agent.JsonRpc;
import com.leavesdeepseek.agent.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.PlayerInventory;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@code act.craft}：在工作台（或 2x2）配方里找一个能做的，扣材料、给成品。
 *
 * <p>只处理 shaped / shapeless 两种工作台配方；材料不够时报 {@code NOT_HOLDING} 并带上缺什么。</p>
 */
public final class CraftTask extends AbstractTask {

    private final Material material;
    private final int count;
    private final Map<String, Integer> missing = new HashMap<>();

    public CraftTask(Material material, int count) {
        this.material = material;
        this.count = Math.max(1, Math.min(64, count));
        this.deadline = startedAt + 5_000L;
    }

    @Override
    public String type() {
        return "craft";
    }

    @Override
    public void start(AIBody body) {
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳不在线");
            return;
        }
        Recipe recipe = null;
        List<ItemStack> needs = null;
        boolean hasTable = craftingTableNearby(bot, 4);
        boolean sawTableRecipe = false;
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe candidate = iterator.next();
            if (candidate.getResult().getType() != material) {
                continue;
            }
            List<ItemStack> ingredients = ingredientsOf(candidate);
            if (ingredients == null) {
                continue;
            }
            boolean table = needsTable(candidate);
            if (table) {
                sawTableRecipe = true;
            }
            if (table && !hasTable) {
                // 3x3 配方必须有工作台（真玩家也做不到凭空合成）
                continue;
            }
            if (recipe == null) {
                recipe = candidate;
                needs = ingredients;
            }
            if (hasAll(bot.getInventory(), ingredients, count)) {
                recipe = candidate;
                needs = ingredients;
                break;
            }
        }
        if (recipe == null || needs == null) {
            if (sawTableRecipe) {
                fail(JsonRpc.NOT_FOUND, "这个配方需要工作台（半径 4 格内没有 crafting_table）");
            } else {
                fail(JsonRpc.NOT_FOUND, "没有 " + material.name() + " 的工作台配方");
            }
            return;
        }
        PlayerInventory inventory = bot.getInventory();
        if (!hasAll(inventory, needs, count)) {
            fail(JsonRpc.NOT_HOLDING, "材料不够");
            return;
        }
        for (ItemStack need : needs) {
            if (need == null || need.getType().isAir()) {
                continue;
            }
            ItemUtil.remove(inventory, need.getType(), Math.max(1, need.getAmount()) * count);
        }
        ItemStack result = recipe.getResult().clone();
        result.setAmount(result.getAmount() * count);
        Map<Integer, ItemStack> leftover = inventory.addItem(result);
        if (!leftover.isEmpty() && bot.getWorld() != null) {
            for (ItemStack stack : leftover.values()) {
                bot.getWorld().dropItemNaturally(bot.getLocation(), stack);
            }
        }
        finish();
    }

    private static List<ItemStack> ingredientsOf(Recipe recipe) {
        if (recipe instanceof ShapelessRecipe shapeless) {
            return new ArrayList<>(shapeless.getIngredientList());
        }
        if (recipe instanceof ShapedRecipe shaped) {
            return new ArrayList<>(shaped.getIngredientMap().values());
        }
        return null;
    }

    /** 3x3 配方必须站在工作台旁。 */
    private static boolean needsTable(Recipe recipe) {
        if (recipe instanceof ShapedRecipe shaped) {
            String[] shape = shaped.getShape();
            if (shape == null) {
                return false;
            }
            if (shape.length > 2) {
                return true;
            }
            for (String row : shape) {
                if (row != null && row.length() > 2) {
                    return true;
                }
            }
            return false;
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            return shapeless.getIngredientList().size() > 4;
        }
        return false;
    }

    private static boolean craftingTableNearby(Bot bot, int radius) {
        org.bukkit.Location location = bot.getLocation();
        org.bukkit.World world = location.getWorld();
        if (world == null) {
            return false;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (world.getBlockAt(location.getBlockX() + dx, location.getBlockY() + dy,
                            location.getBlockZ() + dz).getType() == Material.CRAFTING_TABLE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasAll(PlayerInventory inventory, List<ItemStack> needs, int times) {
        boolean ok = true;
        for (ItemStack need : needs) {
            if (need == null || need.getType().isAir()) {
                continue;
            }
            int required = Math.max(1, need.getAmount()) * times;
            int have = ItemUtil.countOf(inventory, need.getType());
            if (have < required) {
                missing.merge(need.getType().name().toLowerCase(java.util.Locale.ROOT), required - have, Integer::sum);
                ok = false;
            }
        }
        return ok;
    }

    @Override
    public void tick(AIBody body) {
        // craft 是瞬时完成的，start() 里已经结束
        if (!done() && !failed()) {
            finish();
        }
    }

    @Override
    public double progress() {
        return done() ? 1.0 : 0.0;
    }

    @Override
    public com.google.gson.JsonObject extraData() {
        if (missing.isEmpty()) {
            return null;
        }
        com.google.gson.JsonObject object = new com.google.gson.JsonObject();
        com.google.gson.JsonObject gaps = new com.google.gson.JsonObject();
        missing.forEach(gaps::addProperty);
        object.add("missing", gaps);
        return object;
    }

    @Override
    public void cancel() {
        fail(JsonRpc.TIMEOUT, "CANCELLED");
    }
}
