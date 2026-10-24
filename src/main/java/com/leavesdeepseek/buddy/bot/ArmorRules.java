package com.leavesdeepseek.buddy.bot;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 盔甲槽位与“哪一件更好”的判定。
 *
 * <p>评分规则：护甲点 ×1000 + 材质等级 ×100 + 保护类附魔等级 ×10。
 * 附魔加分刻意小于一级材质差，保证“更高级的甲”一定胜过“低一级但有附魔的甲”，
 * 同材质时再比附魔。</p>
 */
public final class ArmorRules {

    /** 41 格背包中的装备槽下标（0-35 主背包，36 靴子，37 护腿，38 胸甲，39 头盔，40 副手）。 */
    public static final int BOOTS = 36;
    public static final int LEGGINGS = 37;
    public static final int CHESTPLATE = 38;
    public static final int HELMET = 39;

    /** 顺序固定为：头盔、胸甲、护腿、靴子（便于输出）。 */
    public static final List<Integer> ALL_SLOTS = List.of(HELMET, CHESTPLATE, LEGGINGS, BOOTS);

    private ArmorRules() {
    }

    /**
     * 把 tool 传入的槽位名转成背包下标；不认识的返回 -1。
     */
    public static int slotIndexOf(String name) {
        if (name == null) {
            return -1;
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "helmet", "head", "头盔" -> HELMET;
            case "chestplate", "chest", "胸甲" -> CHESTPLATE;
            case "leggings", "legs", "护腿" -> LEGGINGS;
            case "boots", "feet", "靴子" -> BOOTS;
            default -> -1;
        };
    }

    public static String slotName(int slot) {
        return switch (slot) {
            case HELMET -> "helmet";
            case CHESTPLATE -> "chestplate";
            case LEGGINGS -> "leggings";
            case BOOTS -> "boots";
            default -> "unknown";
        };
    }

    public static String slotLabel(int slot) {
        return switch (slot) {
            case HELMET -> "头盔";
            case CHESTPLATE -> "胸甲";
            case LEGGINGS -> "护腿";
            case BOOTS -> "靴子";
            default -> "装备";
        };
    }

    /**
     * 该物品在该槽位的评分；0 表示放不进这个槽位（不是盔甲、或者槽位不匹配）。
     */
    public static int score(ItemStack stack, int slot) {
        if (stack == null || stack.getType().isAir()) {
            return 0;
        }
        Material type = stack.getType();
        int[] stats = stats(type);
        if (stats == null || stats[0] != slot) {
            return 0;
        }
        int protection = stats[1];
        int tier = stats[2];
        return protection * 1000 + tier * 100 + protectionEnchant(stack);
    }

    /**
     * 主背包（0-35）里最适合该槽位的一件；没有返回 null。
     */
    public static ItemStack bestIn(Inventory inventory, int slot) {
        int bestSlot = bestSlotIn(inventory, slot);
        return bestSlot < 0 ? null : inventory.getItem(bestSlot);
    }

    public static int bestSlotIn(Inventory inventory, int slot) {
        if (inventory == null) {
            return -1;
        }
        ItemStack[] contents = inventory.getContents();
        int limit = Math.min(36, contents.length);
        int bestSlot = -1;
        int bestScore = 0;
        for (int index = 0; index < limit; index++) {
            int score = score(contents[index], slot);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = index;
            }
        }
        return bestSlot;
    }

    public static int firstEmptyStorageSlot(Inventory inventory) {
        if (inventory == null) {
            return -1;
        }
        ItemStack[] contents = inventory.getContents();
        int limit = Math.min(36, contents.length);
        for (int index = 0; index < limit; index++) {
            ItemStack stack = contents[index];
            if (stack == null || stack.getType().isAir()) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 简短描述，例如 {@code DIAMOND_CHESTPLATE(保护III)}。
     */
    public static String describe(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return "空";
        }
        StringBuilder builder = new StringBuilder(stack.getType().name());
        int protection = protectionEnchant(stack);
        if (protection > 0) {
            builder.append("(保护").append(roman(protection / 10)).append(')');
        }
        if (stack.getAmount() > 1) {
            builder.append("×").append(stack.getAmount());
        }
        return builder.toString();
    }

    /**
     * 保护类附魔等级 ×10 的编码（取最强的那个）。
     */
    private static int protectionEnchant(ItemStack stack) {
        int level = 0;
        try {
            level = Math.max(level, stack.getEnchantmentLevel(Enchantment.PROTECTION));
            level = Math.max(level, stack.getEnchantmentLevel(Enchantment.BLAST_PROTECTION));
            level = Math.max(level, stack.getEnchantmentLevel(Enchantment.FIRE_PROTECTION));
            level = Math.max(level, stack.getEnchantmentLevel(Enchantment.PROJECTILE_PROTECTION));
        } catch (RuntimeException ignored) {
            // 自定义物品可能不支持附魔查询
        }
        return level * 10;
    }

    /**
     * 单个槽位的换装结果。
     *
     * @param slot 槽位名（helmet/chestplate/...）
     * @param from 换下来的物品描述
     * @param to   穿上的物品描述
     */
    public record Change(String slot, String from, String to) {
    }

    /**
     * 一次换装的完整报告。
     *
     * @param changes 实际发生的换装
     * @param notes   没有换装的原因说明（可直接回给模型）
     */
    public record Report(List<Change> changes, List<String> notes) {

        public boolean changed() {
            return !changes.isEmpty();
        }
    }

    /**
     * 给假人换盔甲。规则：
     * <ol>
     *   <li>槽位为空 → 穿上背包里最好的一件；</li>
     *   <li>槽位已有 → 只有背包里严格更好时才换；</li>
     *   <li>否则保持原样，并在 notes 里说明原因。</li>
     * </ol>
     * 换下来的旧盔甲会放回候选装备腾出的那一格，不会丢在地上。
     */
    public static Report equip(Bot bot, List<Integer> slots) {
        List<Change> changes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (bot == null || !bot.isValid()) {
            notes.add("假人已经不存在了");
            return new Report(changes, notes);
        }
        PlayerInventory inventory;
        try {
            inventory = bot.getInventory();
        } catch (Throwable throwable) {
            notes.add("无法访问背包：" + throwable.getMessage());
            return new Report(changes, notes);
        }

        for (int slot : slots) {
            String label = slotLabel(slot);
            ItemStack current = inventory.getItem(slot);
            int currentScore = score(current, slot);
            int bestSlot = bestSlotIn(inventory, slot);
            if (bestSlot < 0) {
                if (currentScore == 0) {
                    notes.add(label + "槽位空着，背包里也没有可用的盔甲");
                } else {
                    notes.add(label + "已经有 " + describe(current) + "，背包里没有别的盔甲");
                }
                continue;
            }
            ItemStack candidate = inventory.getItem(bestSlot);
            if (candidate == null || candidate.getType().isAir()) {
                continue;
            }
            if (score(candidate, slot) <= currentScore) {
                notes.add(label + "保持 " + describe(current) + "，背包里的 " + describe(candidate) + " 并不更好");
                continue;
            }

            inventory.setItem(slot, candidate);
            inventory.setItem(bestSlot, current);
            ItemStack after = inventory.getItem(slot);
            if (after == null || after.getType() != candidate.getType()) {
                // 服务端没有接受这次修改（假人物品同步有延迟），回滚，避免吞物品
                inventory.setItem(slot, current);
                inventory.setItem(bestSlot, candidate);
                notes.add(label + "穿上失败（服务端没有接受这次修改），保持 " + describe(current));
                continue;
            }
            changes.add(new Change(slotName(slot), describe(current), describe(candidate)));
        }
        return new Report(changes, notes);
    }

    /**
     * @return {槽位下标, 护甲点, 材质等级}；不是标准盔甲返回 null
     */
    private static int[] stats(Material type) {
        String name = type.name();
        return switch (name) {
            // ---- 头盔 ----
            case "LEATHER_HELMET" -> new int[]{HELMET, 1, 1};
            case "GOLDEN_HELMET" -> new int[]{HELMET, 2, 2};
            case "CHAINMAIL_HELMET" -> new int[]{HELMET, 2, 3};
            case "IRON_HELMET" -> new int[]{HELMET, 2, 4};
            case "TURTLE_HELMET" -> new int[]{HELMET, 2, 4};
            case "DIAMOND_HELMET" -> new int[]{HELMET, 3, 5};
            case "NETHERITE_HELMET" -> new int[]{HELMET, 3, 6};
            // ---- 胸甲 ----
            case "LEATHER_CHESTPLATE" -> new int[]{CHESTPLATE, 3, 1};
            case "GOLDEN_CHESTPLATE" -> new int[]{CHESTPLATE, 5, 2};
            case "CHAINMAIL_CHESTPLATE" -> new int[]{CHESTPLATE, 5, 3};
            case "IRON_CHESTPLATE" -> new int[]{CHESTPLATE, 6, 4};
            case "DIAMOND_CHESTPLATE" -> new int[]{CHESTPLATE, 8, 5};
            case "NETHERITE_CHESTPLATE" -> new int[]{CHESTPLATE, 8, 6};
            // ---- 护腿 ----
            case "LEATHER_LEGGINGS" -> new int[]{LEGGINGS, 2, 1};
            case "GOLDEN_LEGGINGS" -> new int[]{LEGGINGS, 3, 2};
            case "CHAINMAIL_LEGGINGS" -> new int[]{LEGGINGS, 4, 3};
            case "IRON_LEGGINGS" -> new int[]{LEGGINGS, 5, 4};
            case "DIAMOND_LEGGINGS" -> new int[]{LEGGINGS, 6, 5};
            case "NETHERITE_LEGGINGS" -> new int[]{LEGGINGS, 6, 6};
            // ---- 靴子 ----
            case "LEATHER_BOOTS" -> new int[]{BOOTS, 1, 1};
            case "GOLDEN_BOOTS" -> new int[]{BOOTS, 1, 2};
            case "CHAINMAIL_BOOTS" -> new int[]{BOOTS, 1, 3};
            case "IRON_BOOTS" -> new int[]{BOOTS, 2, 4};
            case "DIAMOND_BOOTS" -> new int[]{BOOTS, 3, 5};
            case "NETHERITE_BOOTS" -> new int[]{BOOTS, 3, 6};
            default -> null;
        };
    }

    private static String roman(int value) {
        return switch (value) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(value);
        };
    }
}
