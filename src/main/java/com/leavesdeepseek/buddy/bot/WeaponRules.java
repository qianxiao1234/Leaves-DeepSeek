package com.leavesdeepseek.buddy.bot;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

/**
 * 战斗装备选择：按 DPS = (基础伤害 + 附魔加成) × 攻击速度 排序，
 * 耐久低于 10% 的武器会被跳过。
 */
public final class WeaponRules {

    private WeaponRules() {
    }

    public record Choice(int slot, double dps) {
    }

    public static int bestSlot(Inventory inventory) {
        return best(inventory).slot();
    }

    public static Choice best(Inventory inventory) {
        Choice best = new Choice(-1, 0.0);
        if (inventory == null) {
            return best;
        }
        ItemStack[] contents = inventory.getContents();
        int limit = Math.min(36, contents.length);
        for (int slot = 0; slot < limit; slot++) {
            double dps = dps(contents[slot]);
            if (dps > best.dps()) {
                best = new Choice(slot, dps);
            }
        }
        return best;
    }

    public static double dps(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || isNearlyBroken(stack)) {
            return 0.0;
        }
        double[] stats = stats(stack.getType());
        if (stats == null) {
            return 0.0;
        }
        int sharpness = 0;
        try {
            sharpness = stack.getEnchantmentLevel(Enchantment.SHARPNESS);
        } catch (RuntimeException ignored) {
            // 某些自定义物品可能不支持附魔查询
        }
        double damage = stats[0];
        if (sharpness > 0) {
            damage += 1.0 + 0.5 * (sharpness - 1);
        }
        return damage * stats[1];
    }

    /**
     * 耐久低于 10% 时不再作为候选武器。
     */
    public static boolean isNearlyBroken(ItemStack stack) {
        try {
            var meta = stack.getItemMeta();
            if (meta == null || meta.isUnbreakable() || !(meta instanceof Damageable damageable)) {
                return false;
            }
            short max = stack.getType().getMaxDurability();
            if (max <= 0) {
                return false;
            }
            int remaining = max - damageable.getDamage();
            return remaining < max * 0.1;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * 该物品的攻击速度（次/秒），非武器返回 4.0（等同空手）。
     */
    public static double attackSpeed(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return 4.0;
        }
        double[] stats = stats(stack.getType());
        return stats == null ? 4.0 : stats[1];
    }

    /**
     * 按照原作攻速换算出的攻击冷却 tick（剑 1.6 → 13 tick，斧 1.0 → 20 tick）。
     */
    public static int cooldownTicks(ItemStack stack) {
        double speed = Math.max(0.1, attackSpeed(stack));
        return (int) Math.ceil(20.0 / speed);
    }

    /**
     * 返回背包中盾牌的槽位，没有则 -1。
     */    public static int shieldSlot(Inventory inventory) {
        if (inventory == null) {
            return -1;
        }
        ItemStack[] contents = inventory.getContents();
        int limit = Math.min(36, contents.length);
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = contents[slot];
            if (stack != null && stack.getType() == Material.SHIELD) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * {基础伤害, 攻击速度}
     */
    private static double[] stats(Material material) {
        return switch (material) {
            case NETHERITE_SWORD -> new double[]{8.0, 1.6};
            case DIAMOND_SWORD -> new double[]{7.0, 1.6};
            case IRON_SWORD -> new double[]{6.0, 1.6};
            case STONE_SWORD -> new double[]{5.0, 1.6};
            case GOLDEN_SWORD -> new double[]{4.0, 1.6};
            case WOODEN_SWORD -> new double[]{4.0, 1.6};
            case NETHERITE_AXE -> new double[]{10.0, 1.0};
            case DIAMOND_AXE -> new double[]{9.0, 1.0};
            case IRON_AXE -> new double[]{9.0, 0.9};
            case STONE_AXE -> new double[]{9.0, 0.8};
            case GOLDEN_AXE -> new double[]{7.0, 1.0};
            case WOODEN_AXE -> new double[]{7.0, 0.8};
            case MACE -> new double[]{6.0, 0.6};
            case TRIDENT -> new double[]{9.0, 1.1};
            case NETHERITE_PICKAXE -> new double[]{6.0, 1.2};
            case DIAMOND_PICKAXE -> new double[]{5.0, 1.2};
            case IRON_PICKAXE -> new double[]{4.0, 1.2};
            case STONE_PICKAXE -> new double[]{3.0, 1.2};
            case GOLDEN_PICKAXE -> new double[]{2.0, 1.2};
            case WOODEN_PICKAXE -> new double[]{2.0, 1.2};
            case NETHERITE_SHOVEL -> new double[]{6.5, 1.0};
            case DIAMOND_SHOVEL -> new double[]{5.5, 1.0};
            case IRON_SHOVEL -> new double[]{4.5, 1.0};
            case STONE_SHOVEL -> new double[]{3.5, 1.0};
            case GOLDEN_SHOVEL -> new double[]{2.5, 1.0};
            case WOODEN_SHOVEL -> new double[]{2.5, 1.0};
            default -> null;
        };
    }
}
