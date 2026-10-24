package com.leavesdeepseek.buddy.bot;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 食物规则：区分普通食物与需要主人同意的贵重食物。
 */
public final class FoodRules {

    private static final Set<Material> VALUABLE = EnumSet.of(
            Material.ENCHANTED_GOLDEN_APPLE,
            Material.GOLDEN_APPLE,
            Material.GOLDEN_CARROT);

    private static final Set<Material> AVOID = EnumSet.of(
            Material.ROTTEN_FLESH,
            Material.SPIDER_EYE,
            Material.POISONOUS_POTATO,
            Material.PUFFERFISH,
            Material.SUSPICIOUS_STEW,
            Material.CHORUS_FRUIT);

    private static final List<Material> PREFERRED = List.of(
            Material.COOKED_BEEF,
            Material.COOKED_PORKCHOP,
            Material.COOKED_MUTTON,
            Material.COOKED_CHICKEN,
            Material.COOKED_RABBIT,
            Material.COOKED_COD,
            Material.COOKED_SALMON,
            Material.BREAD,
            Material.BAKED_POTATO,
            Material.CARROT,
            Material.APPLE,
            Material.MELON_SLICE,
            Material.SWEET_BERRIES,
            Material.GLOW_BERRIES,
            Material.DRIED_KELP,
            Material.POTATO,
            Material.BEETROOT,
            Material.BEETROOT_SOUP,
            Material.MUSHROOM_STEW,
            Material.RABBIT_STEW);

    private FoodRules() {
    }

    public static boolean isValuable(Material material) {
        return material != null && VALUABLE.contains(material);
    }

    public static boolean isSafeFood(Material material) {
        return material != null
                && material.isEdible()
                && !VALUABLE.contains(material)
                && !AVOID.contains(material);
    }

    /**
     * 从背包里挑一个适合自动吃的食物；没有返回 null。
     */
    public static Material pickBest(Inventory inventory, boolean allowValuable) {
        if (inventory == null) {
            return null;
        }
        for (Material preferred : PREFERRED) {
            if (contains(inventory, preferred)) {
                return preferred;
            }
        }
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null) {
                continue;
            }
            Material material = stack.getType();
            if (!material.isEdible() || AVOID.contains(material)) {
                continue;
            }
            if (!allowValuable && VALUABLE.contains(material)) {
                continue;
            }
            return material;
        }
        return null;
    }

    /**
     * 判断主人是否在本次消息里明确同意/要求吃这种贵重食物。
     *
     * <p>分两种情况：</p>
     * <ol>
     *   <li>整条消息就是一个纯肯定词（"确认"、"可以"、"好"、"行"、"嗯"…）——
     *       假人刚问过"你确认让我吃吗"，主人回一个字也算同意；</li>
     *   <li>句子里同时出现「吃」和明确的许可词（"给你吃"、"拿去吃"、"可以吃"…）。</li>
     * </ol>
     */
    public static boolean ownerApproved(String userMessage, Material material) {
        if (userMessage == null || userMessage.isBlank() || material == null) {
            return false;
        }
        // 去掉空白和中文标点后再判断，避免"确认。"、"好的 ~" 之类漏判
        String text = userMessage.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s。，,、.!！?？~～…]+", "");
        if (text.isEmpty()) {
            return false;
        }
        boolean refusal = text.contains("不行")
                || text.contains("不可以")
                || text.contains("不要")
                || text.contains("不准")
                || text.contains("不同意")
                || text.contains("别吃")
                || text.contains("留着")
                || text.contains("拒绝")
                || text.contains("算了");
        if (refusal) {
            return false;
        }
        // 情况一：整条消息就是一句肯定
        if (PURE_YES.contains(text)) {
            return true;
        }
        // 情况二：句子里必须有「吃」这个动作
        if (!text.contains("吃") && !text.contains("eat")) {
            return false;
        }
        boolean approval = text.contains("可以")
                || text.contains("同意")
                || text.contains("允许")
                || text.contains("批准")
                || text.contains("确认")
                || text.contains("确定")
                || text.contains("没问题")
                || text.contains("你吃")
                || text.contains("拿去")
                || text.contains("给你")
                || text.contains("直接吃")
                || text.contains("就吃")
                || text.contains("吃吧")
                || text.contains("吃啊")
                || text.contains("行")
                || text.contains("好");
        boolean named = text.contains(material.name().toLowerCase(Locale.ROOT));
        if (material == Material.GOLDEN_APPLE) {
            named |= text.contains("金苹果") && !text.contains("附魔");
        } else if (material == Material.ENCHANTED_GOLDEN_APPLE) {
            named |= text.contains("附魔金苹果") || text.contains("附魔");
        } else if (material == Material.GOLDEN_CARROT) {
            named |= text.contains("金胡萝卜");
        }
        return approval || named;
    }

    /** 整条消息就是一句纯粹的"同意"时，直接视为授权。 */
    private static final Set<String> PURE_YES = Set.of(
            "确认", "确定", "可以", "可以可以", "同意", "允许", "批准", "准了",
            "好", "好的", "好啊", "好吧", "行", "行吧", "没问题", "是", "是的",
            "嗯", "嗯嗯", "吃", "吃吧", "给你", "拿去", "ok", "okay", "yes");

    private static boolean contains(Inventory inventory, Material material) {
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == material) {
                return true;
            }
        }
        return false;
    }
}
