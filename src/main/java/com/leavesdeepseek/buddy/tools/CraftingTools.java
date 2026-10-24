package com.leavesdeepseek.buddy.tools;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.ai.AgentTool;
import com.leavesdeepseek.buddy.ai.Schema;
import com.leavesdeepseek.buddy.ai.Tools;
import com.leavesdeepseek.buddy.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 合成工具。
 * <p>2×2 以内直接背包合成；3×3 或材料超过 4 个时需要附近有工作台（模拟真实使用工作台）。</p>
 */
public final class CraftingTools {

    /** 假人可操作的背包槽位：快捷栏 + 主背包，不含盔甲与副手 */
    private static final int MAIN_INVENTORY_SIZE = 36;

    private final LeavesDeepSeekPlugin plugin;

    public CraftingTools(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public List<AgentTool> all() {
        return List.of(craftItem(), listCraftable());
    }

    private AgentTool craftItem() {
        Schema schema = Schema.object()
                .str("item", "要合成的物品名，例如 STICK、OAK_PLANKS、TORCH、IRON_PICKAXE")
                .integer("amount", "想要得到多少个成品，默认 1")
                .require("item");
        return Tools.of("craft_item", "用背包里的材料合成物品。2×2 配方可直接合成，3×3 配方需要附近有工作台。", schema,
                (context, arguments) -> craft(context, arguments));
    }

    private AgentTool listCraftable() {
        return Tools.of("list_craftable", "查看当前背包材料现在能合成哪些物品（只列出可合成的配方结果）。",
                Schema.object(), (context, arguments) -> {
                    Bot bot = context.bot();
                    boolean hasWorkbench = findWorkbench(bot, plugin.settings().craftWorkbenchRadius()) != null;

                    Set<String> results = new LinkedHashSet<>();
                    var iterator = Bukkit.recipeIterator();
                    while (iterator.hasNext() && results.size() < 30) {
                        Recipe recipe = iterator.next();
                        List<RecipeChoice> requirements = requirements(recipe);
                        if (requirements == null) {
                            continue;
                        }
                        if (needsWorkbench(recipe, requirements)
                                && plugin.settings().craftRequireWorkbench() && !hasWorkbench) {
                            continue;
                        }
                        if (canCraftOnce(bot, requirements)) {
                            results.add(recipe.getResult().getType().name());
                        }
                    }
                    if (results.isEmpty()) {
                        return done(Json.ok("当前没有可以合成的物品"));
                    }
                    return done(Json.ok("当前可以合成", "items", results));
                });
    }

    private CompletableFuture<String> craft(com.leavesdeepseek.buddy.ai.ToolContext context,
                                            com.google.gson.JsonObject arguments) {
        if (!plugin.settings().craftEnabled()) {
            return done(Json.error("服务器配置禁止假人合成物品"));
        }
        Bot bot = context.bot();
        String itemName = Json.getString(arguments, "item", "").trim().toUpperCase(Locale.ROOT);
        Material material = Material.matchMaterial(itemName);
        if (material == null || material.isAir() || !material.isItem()) {
            return done(Json.error("无效的物品名：" + itemName));
        }

        int wanted = Math.max(1, Math.min(2304, Json.getInt(arguments, "amount", 1)));
        List<Recipe> recipes = new ArrayList<>(Bukkit.getServer().getRecipesFor(new ItemStack(material)));
        recipes.removeIf(recipe -> requirements(recipe) == null);
        if (recipes.isEmpty()) {
            return done(Json.error(material.name() + " 不是可合成的物品"));
        }

        Inventory inventory = bot.getInventory();
        InventoryView view = new InventoryView(inventory);

        String lastMissing = null;
        for (Recipe recipe : recipes) {
            List<RecipeChoice> requirements = requirements(recipe);
            if (requirements == null) {
                continue;
            }
            boolean needsWorkbench = needsWorkbench(recipe, requirements);
            if (needsWorkbench && plugin.settings().craftRequireWorkbench()
                    && findWorkbench(bot, plugin.settings().craftWorkbenchRadius()) == null) {
                lastMissing = "这个配方需要工作台，附近 " + plugin.settings().craftWorkbenchRadius()
                        + " 格内没有工作台。请先用 place_block 放一个 CRAFTING_TABLE。";
                continue;
            }

            int resultAmount = Math.max(1, recipe.getResult().getAmount());
            int times = (int) Math.ceil(wanted / (double) resultAmount);
            times = Math.max(1, Math.min(64, times));

            Map<Integer, Integer> costs = new HashMap<>();
            List<String> missing = new ArrayList<>();
            int crafted = 0;
            for (int index = 0; index < times; index++) {
                Map<Integer, Integer> iterationCosts = new HashMap<>(costs);
                List<String> iterationMissing = new ArrayList<>();
                for (RecipeChoice requirement : requirements) {
                    int slot = view.findSlot(requirement, iterationCosts);
                    if (slot < 0) {
                        iterationMissing.add(describe(requirement));
                    } else {
                        iterationCosts.merge(slot, 1, Integer::sum);
                    }
                }
                if (!iterationMissing.isEmpty()) {
                    missing = iterationMissing;
                    break;
                }
                costs = iterationCosts;
                crafted++;
            }

            if (crafted == 0) {
                lastMissing = "材料不足，缺少：" + String.join("、", missing);
                continue;
            }

            ItemStack result = recipe.getResult().clone();
            result.setAmount(resultAmount * crafted);
            if (view.freeCapacityAfter(costs, result) < result.getAmount()) {
                return done(Json.error("背包空间不够放下 " + result.getAmount() + " 个 " + material.name()
                        + "，请先清理背包或减少数量"));
            }

            view.consume(costs);
            int remaining = view.insert(result);
            World world = bot.getWorld();
            if (remaining > 0 && world != null) {
                ItemStack overflow = result.clone();
                overflow.setAmount(remaining);
                world.dropItemNaturally(bot.getLocation().add(0, 1, 0), overflow);
            }

            String message = "合成完成：" + material.name() + " ×" + result.getAmount()
                    + (needsWorkbench && plugin.settings().craftRequireWorkbench() ? "（使用了工作台）" : "（背包合成）");
            if (crafted < times) {
                message += "；材料只够合成 " + crafted + " 次";
            }
            if (remaining > 0) {
                message += "；背包已满，" + remaining + " 个掉在了脚下";
            }
            return done(Json.ok(message,
                    "item", material.name(),
                    "amount", result.getAmount(),
                    "times", crafted,
                    "used_workbench", needsWorkbench));
        }

        return done(Json.error(lastMissing == null ? "找不到可用的合成配方" : lastMissing));
    }

    private boolean canCraftOnce(Bot bot, List<RecipeChoice> requirements) {
        InventoryView view = new InventoryView(bot.getInventory());
        Map<Integer, Integer> costs = new HashMap<>();
        for (RecipeChoice requirement : requirements) {
            int slot = view.findSlot(requirement, costs);
            if (slot < 0) {
                return false;
            }
            costs.merge(slot, 1, Integer::sum);
        }
        return true;
    }

    /**
     * 把配方拆成“每个材料格消耗一个物品”的列表。
     *
     * @return 非合成配方返回 null
     */
    private List<RecipeChoice> requirements(Recipe recipe) {
        List<RecipeChoice> requirements = new ArrayList<>();
        if (recipe instanceof ShapedRecipe shaped) {
            Map<Character, RecipeChoice> map = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (char character : row.toCharArray()) {
                    if (character == ' ') {
                        continue;
                    }
                    RecipeChoice choice = map.get(character);
                    if (choice != null && !isEmptyChoice(choice)) {
                        requirements.add(choice);
                    }
                }
            }
            return requirements;
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            for (RecipeChoice choice : shapeless.getChoiceList()) {
                if (choice != null && !isEmptyChoice(choice)) {
                    requirements.add(choice);
                }
            }
            return requirements;
        }
        return null;
    }

    private boolean needsWorkbench(Recipe recipe, List<RecipeChoice> requirements) {
        if (recipe instanceof ShapedRecipe shaped) {
            String[] shape = shaped.getShape();
            int width = 0;
            for (String row : shape) {
                width = Math.max(width, row.length());
            }
            return width > 2 || shape.length > 2;
        }
        return requirements.size() > 4;
    }

    private boolean isEmptyChoice(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
            return materialChoice.getChoices().isEmpty()
                    || materialChoice.getChoices().stream().allMatch(Material::isAir);
        }
        if (choice instanceof RecipeChoice.ExactChoice exactChoice) {
            return exactChoice.getChoices().isEmpty()
                    || exactChoice.getChoices().stream().allMatch(stack -> stack.getType().isAir());
        }
        return false;
    }

    private String describe(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.MaterialChoice materialChoice && !materialChoice.getChoices().isEmpty()) {
            return materialChoice.getChoices().get(0).name();
        }
        if (choice instanceof RecipeChoice.ExactChoice exactChoice && !exactChoice.getChoices().isEmpty()) {
            return exactChoice.getChoices().get(0).getType().name();
        }
        return "未知材料";
    }

    private Block findWorkbench(Bot bot, int radius) {
        World world = bot.getWorld();
        if (world == null) {
            return null;
        }
        Location location = bot.getLocation();
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                    if (block.getType() == Material.CRAFTING_TABLE) {
                        return block;
                    }
                }
            }
        }
        return null;
    }

    private boolean matches(ItemStack stack, RecipeChoice choice) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
            return materialChoice.getChoices().contains(stack.getType());
        }
        if (choice instanceof RecipeChoice.ExactChoice exactChoice) {
            for (ItemStack candidate : exactChoice.getChoices()) {
                if (candidate.isSimilar(stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    private CompletableFuture<String> done(String value) {
        return CompletableFuture.completedFuture(value);
    }

    /**
     * 背包视图：在副本上模拟消耗，确认无误后再真正扣除。
     */
    private final class InventoryView {

        private final Inventory inventory;

        private InventoryView(Inventory inventory) {
            this.inventory = inventory;
        }

        private int findSlot(RecipeChoice requirement, Map<Integer, Integer> costs) {
            int limit = Math.min(MAIN_INVENTORY_SIZE, inventory.getSize());
            for (int slot = 0; slot < limit; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!matches(stack, requirement)) {
                    continue;
                }
                int used = costs.getOrDefault(slot, 0);
                if (stack.getAmount() - used > 0) {
                    return slot;
                }
            }
            return -1;
        }

        private void consume(Map<Integer, Integer> costs) {
            for (Map.Entry<Integer, Integer> entry : costs.entrySet()) {
                ItemStack stack = inventory.getItem(entry.getKey());
                if (stack == null) {
                    continue;
                }
                int left = stack.getAmount() - entry.getValue();
                if (left <= 0) {
                    inventory.setItem(entry.getKey(), null);
                } else {
                    ItemStack updated = stack.clone();
                    updated.setAmount(left);
                    inventory.setItem(entry.getKey(), updated);
                }
            }
        }

        private int freeCapacityAfter(Map<Integer, Integer> costs, ItemStack result) {
            int capacity = 0;
            int maxStackSize = result.getMaxStackSize();
            int limit = Math.min(MAIN_INVENTORY_SIZE, inventory.getSize());
            for (int slot = 0; slot < limit; slot++) {
                ItemStack stack = inventory.getItem(slot);
                int remaining = stack == null || stack.getType().isAir()
                        ? 0
                        : stack.getAmount() - costs.getOrDefault(slot, 0);
                if (remaining <= 0) {
                    capacity += maxStackSize;
                } else if (stack.isSimilar(result)) {
                    capacity += Math.max(0, maxStackSize - remaining);
                }
            }
            return capacity;
        }

        /**
         * 只往快捷栏与主背包放入物品，避免占掉盔甲/副手槽位。
         *
         * @return 放不下的数量
         */
        private int insert(ItemStack result) {
            int remaining = result.getAmount();
            int maxStackSize = result.getMaxStackSize();
            int limit = Math.min(MAIN_INVENTORY_SIZE, inventory.getSize());

            for (int slot = 0; slot < limit && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack == null || stack.getType().isAir() || !stack.isSimilar(result)) {
                    continue;
                }
                int space = maxStackSize - stack.getAmount();
                if (space <= 0) {
                    continue;
                }
                int move = Math.min(space, remaining);
                ItemStack updated = stack.clone();
                updated.setAmount(stack.getAmount() + move);
                inventory.setItem(slot, updated);
                remaining -= move;
            }

            for (int slot = 0; slot < limit && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack != null && !stack.getType().isAir()) {
                    continue;
                }
                int move = Math.min(maxStackSize, remaining);
                ItemStack placed = result.clone();
                placed.setAmount(move);
                inventory.setItem(slot, placed);
                remaining -= move;
            }
            return remaining;
        }
    }
}
