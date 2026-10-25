package com.leavesdeepseek.agent.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.Locale;

/** 物品名 / 槽位 / 装备 / 食物 的小工具。 */
public final class ItemUtil {

    private ItemUtil() {
    }

    public static String nameOf(ItemStack stack) {
        return stack == null || stack.getType().isAir()
                ? "" : stack.getType().name().toLowerCase(Locale.ROOT);
    }

    /**
     * 宽容的物品名解析：{@code diamond_sword} / {@code DIAMOND_SWORD} /
     * {@code minecraft:diamond_sword} / {@code Diamond Sword} 都认。
     */
    public static Material match(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("minecraft:")) {
            value = value.substring("minecraft:".length());
        }
        value = value.replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        Material material = Material.matchMaterial(value);
        if (material != null) {
            return material;
        }
        // 再兜底一次：去掉下划线匹配（例如 "diamondsword"）
        String compact = value.replace("_", "");
        for (Material candidate : Material.values()) {
            if (!candidate.isLegacy() && candidate.name().replace("_", "").equals(compact)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 对外统一的物品结构（规格 §8.0）：{@code slot/item/count/name/enchant/lore/durability}。
     */
    public static JsonObject describe(ItemStack stack, int slot) {
        JsonObject object = new JsonObject();
        if (stack == null || stack.getType().isAir()) {
            object.addProperty("slot", slot);
            object.add("item", com.google.gson.JsonNull.INSTANCE);
            object.addProperty("count", 0);
            return object;
        }
        object.addProperty("slot", slot);
        object.addProperty("item", nameOf(stack));
        object.addProperty("count", stack.getAmount());
        String displayName = displayNameOf(stack);
        if (displayName != null && !displayName.isBlank()) {
            object.addProperty("name", displayName);
        }
        JsonArray enchants = enchantmentsOf(stack);
        if (enchants.size() > 0) {
            object.add("enchant", enchants);
        }
        JsonArray lore = loreOf(stack);
        if (lore.size() > 0) {
            object.add("lore", lore);
        }
        int durability = durability(stack);
        if (durability >= 0) {
            object.addProperty("durability", durability);
            object.addProperty("maxDurability", stack.getType().getMaxDurability());
        }
        return object;
    }

    /** 自定义显示名（没有则返回 null，不要回落到默认名）。 */
    public static String displayNameOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return null;
        }
        return org.bukkit.ChatColor.stripColor(meta.getDisplayName());
    }

    private static JsonArray enchantmentsOf(ItemStack stack) {
        JsonArray array = new JsonArray();
        if (stack == null || !stack.hasItemMeta()) {
            return array;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasEnchants()) {
            return array;
        }
        for (var entry : meta.getEnchants().entrySet()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", entry.getKey().getKey().getKey());
            object.addProperty("level", entry.getValue());
            array.add(object);
        }
        return array;
    }

    private static JsonArray loreOf(ItemStack stack) {
        JsonArray array = new JsonArray();
        if (stack == null || !stack.hasItemMeta()) {
            return array;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) {
            return array;
        }
        for (String line : meta.getLore()) {
            array.add(org.bukkit.ChatColor.stripColor(line));
        }
        return array;
    }

    /** 剩余耐久；不可损耗物品返回 -1。 */
    public static int durability(ItemStack stack) {
        if (stack == null || stack.getType().getMaxDurability() <= 0) {
            return -1;
        }
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return stack.getType().getMaxDurability();
        }
        return Math.max(0, stack.getType().getMaxDurability() - damageable.getDamage());
    }

    public static int countOf(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    public static int remove(Inventory inventory, Material material, int amount) {
        int left = amount;
        for (int slot = 0; slot < inventory.getSize() && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(left, stack.getAmount());
            left -= take;
            int remain = stack.getAmount() - take;
            if (remain <= 0) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(remain);
                inventory.setItem(slot, stack);
            }
        }
        return amount - left;
    }

    public static boolean isArmor(Material material) {
        String name = material.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || material == Material.ELYTRA || material == Material.CARVED_PUMPKIN;
    }

    /**
     * 把指定物品装备起来：盔甲穿到对应槽位，其它物品放到主手。
     *
     * @return true 表示找到了并装备
     */
    public static boolean equip(Bot bot, Material material) {
        PlayerInventory inventory = bot.getInventory();
        int slot = findSlot(inventory, material);
        if (slot < 0) {
            return false;
        }
        ItemStack stack = inventory.getItem(slot);
        if (stack == null) {
            return false;
        }
        if (isArmor(material)) {
            ItemStack previous = switch (armorSlotOf(material)) {
                case "helmet" -> inventory.getHelmet();
                case "chestplate" -> inventory.getChestplate();
                case "leggings" -> inventory.getLeggings();
                default -> inventory.getBoots();
            };
            inventory.setItem(slot, previous);
            switch (armorSlotOf(material)) {
                case "helmet" -> inventory.setHelmet(stack);
                case "chestplate" -> inventory.setChestplate(stack);
                case "leggings" -> inventory.setLeggings(stack);
                default -> inventory.setBoots(stack);
            }
            return true;
        }
        if (slot < 9) {
            inventory.setHeldItemSlot(slot);
            return true;
        }
        ItemStack held = inventory.getItem(inventory.getHeldItemSlot());
        inventory.setItem(slot, held);
        inventory.setItem(inventory.getHeldItemSlot(), stack);
        return true;
    }

    private static String armorSlotOf(Material material) {
        String name = material.name();
        if (name.endsWith("_HELMET") || material == Material.CARVED_PUMPKIN) {
            return "helmet";
        }
        if (name.endsWith("_CHESTPLATE") || material == Material.ELYTRA) {
            return "chestplate";
        }
        if (name.endsWith("_LEGGINGS")) {
            return "leggings";
        }
        return "boots";
    }

    public static int findSlot(Inventory inventory, Material material) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && stack.getType() == material) {
                return slot;
            }
        }
        for (int slot = 9; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && stack.getType() == material) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * 挑一个能吃的东西。
     *
     * @param allowValuable true 才会挑金苹果类（默认只挑普通食物，贵重食物要显式授权）
     * @param exclude 需要排除的（例如候选清单模式下逐个尝试）
     */
    public static int bestFoodSlot(PlayerInventory inventory, boolean allowValuable,
                                   java.util.Set<Material> exclude) {
        int fallback = -1;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !stack.getType().isEdible()) {
                continue;
            }
            if (exclude != null && exclude.contains(stack.getType())) {
                continue;
            }
            boolean valuable = stack.getType() == Material.GOLDEN_APPLE
                    || stack.getType() == Material.ENCHANTED_GOLDEN_APPLE;
            if (valuable) {
                if (allowValuable) {
                    return slot;
                }
                continue;
            }
            if (fallback < 0) {
                fallback = slot;
            }
        }
        return fallback;
    }

    /** 兼容旧调用：不排除任何东西、允许贵重食物。 */
    public static int bestFoodSlot(PlayerInventory inventory) {
        return bestFoodSlot(inventory, true, null);
    }

    public static int findFoodSlot(PlayerInventory inventory, Material material) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && stack.getType() == material && material.isEdible()) {
                return slot;
            }
        }
        return -1;
    }

    public static int freeSlots(PlayerInventory inventory) {
        int free = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                free++;
            }
        }
        return free;
    }
}
