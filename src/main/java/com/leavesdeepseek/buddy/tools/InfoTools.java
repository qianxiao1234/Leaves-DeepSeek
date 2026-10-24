package com.leavesdeepseek.buddy.tools;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.ai.AgentTool;
import com.leavesdeepseek.buddy.ai.Schema;
import com.leavesdeepseek.buddy.ai.Tools;
import com.leavesdeepseek.buddy.bot.BotNames;
import com.leavesdeepseek.buddy.bot.BotSnapshot;
import com.leavesdeepseek.buddy.bot.FoodRules;
import com.leavesdeepseek.buddy.util.Json;
import com.leavesdeepseek.buddy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 信息查询与背包类工具。
 */
public final class InfoTools {

    private final LeavesDeepSeekPlugin plugin;

    public InfoTools(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public List<AgentTool> all() {
        return List.of(
                getState(),
                findEntities(),
                getInventory(),
                switchItem(),
                useItem(),
                eat(),
                giveItem(),
                pickupItems(),
                dropItem()
        );
    }

    private AgentTool getState() {
        return Tools.of("get_state", "查看自己的位置、血量、背包、附近玩家与生物等完整状态。", Schema.object(),
                (context, arguments) -> {
                    var snapshot = BotSnapshot.capture(context.bot(), context.owner());
                    return done(snapshot.toString());
                });
    }

    private AgentTool findEntities() {
        Schema schema = Schema.object()
                .str("entity_type", "可选的实体类型名，例如 ZOMBIE")
                .integer("radius", "搜索半径，默认 16，最大 32");
        return Tools.of("find_entities", "查看附近有哪些生物以及它们的位置和血量。", schema, (context, arguments) -> {
            Bot bot = context.bot();
            int radius = Math.max(1, Math.min(32, Json.getInt(arguments, "radius", 16)));
            String typeName = Json.getString(arguments, "entity_type", "");

            List<Entity> entities = new ArrayList<>(bot.getNearbyEntities(radius, radius, radius));
            entities.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(bot.getLocation())));

            var results = Json.array();
            for (Entity entity : entities) {
                if (!(entity instanceof LivingEntity living) || living instanceof Player) {
                    continue;
                }
                if (!typeName.isBlank() && !entity.getType().name().equalsIgnoreCase(typeName.trim())) {
                    continue;
                }
                results.add(Json.obj(
                        "type", entity.getType().name(),
                        "distance", Math.round(entity.getLocation().distance(bot.getLocation()) * 10.0) / 10.0,
                        "health", Math.round(living.getHealth() * 10.0) / 10.0,
                        "position", ToolGuards.fmt(entity.getLocation())));
                if (results.size() >= 15) {
                    break;
                }
            }
            if (results.isEmpty()) {
                return done(Json.error("附近没有找到符合条件的生物"));
            }
            return done(Json.ok("找到附近生物", "entities", results));
        });
    }

    private AgentTool getInventory() {
        return Tools.of("get_inventory", "查看背包物品和当前手持物品。", Schema.object(), (context, arguments) -> {
            Bot bot = context.bot();
            var inventory = Json.array();
            ItemStack[] contents = bot.getInventory().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack stack = contents[slot];
                if (stack == null || stack.getType().isAir()) {
                    continue;
                }
                inventory.add(Json.obj("slot", slot, "item", stack.getType().name(), "amount", stack.getAmount()));
                if (inventory.size() >= 40) {
                    break;
                }
            }
            ItemStack hand = bot.getInventory().getItemInMainHand();
            ItemStack offhand = bot.getInventory().getItemInOffHand();
            return done(Json.ok(null,
                    "held_slot", bot.getInventory().getHeldItemSlot(),
                    "main_hand", hand == null ? "empty" : hand.getType().name() + " x" + hand.getAmount(),
                    "off_hand", offhand == null ? "empty" : offhand.getType().name() + " x" + offhand.getAmount(),
                    "items", inventory));
        });
    }

    private AgentTool switchItem() {
        Schema schema = Schema.object()
                .str("item", "物品或方块名，例如 STONE、IRON_PICKAXE")
                .require("item");
        return Tools.of("switch_item", "把背包里的指定物品切换到手上。", schema, (context, arguments) -> {
            String name = Json.getString(arguments, "item", "").trim().toUpperCase(Locale.ROOT);
            Material material = Material.matchMaterial(name);
            if (material == null) {
                return done(Json.error("无效的物品名：" + name));
            }
            int slot = plugin.botActions().ensureInHand(context.bot(), material);
            if (slot < 0) {
                return done(Json.error("背包里没有 " + name));
            }
            return done(Json.ok("已经拿在手上", "item", name, "slot", slot));
        });
    }

    private AgentTool useItem() {
        Schema schema = Schema.object()
                .bool("offhand", "是否使用副手物品，默认 false")
                .integer("ticks", "持续使用的时间（tick），默认 32，例如吃东西约 32");
        return Tools.of("use_item", "使用主手或副手物品，例如吃东西、喝药水、拉弓、举盾。", schema,
                (context, arguments) -> plugin.botActions().useItem(
                        context.bot(),
                        Json.getBoolean(arguments, "offhand", false),
                        Json.getInt(arguments, "ticks", 32)));
    }

    private AgentTool eat() {
        Schema schema = Schema.object()
                .str("item", "要吃的食物名，例如 BREAD；不填则自动选择普通食物");
        return Tools.of("eat", "吃背包里的食物。金苹果、附魔金苹果、金胡萝卜等贵重食物必须先征得主人同意。",
                schema, (context, arguments) -> {
                    String itemName = Json.getString(arguments, "item", "").trim();
                    Material material = null;
                    if (!itemName.isEmpty()) {
                        material = Material.matchMaterial(itemName.toUpperCase(Locale.ROOT));
                        if (material == null || !material.isEdible()) {
                            return done(Json.error("无效的食物名：" + itemName));
                        }
                    }

                    boolean approved = material != null
                            && FoodRules.ownerApproved(context.userMessage(), material);
                    if (material != null && FoodRules.isValuable(material) && !approved) {
                        return done(Json.obj("ok", false, "requires_approval", true,
                                "error", "这是贵重食物（" + material.name()
                                        + "）。请先用 say 询问主人，得到明确同意后再吃").toString());
                    }
                    if (material == null) {
                        boolean hasSafe = FoodRules.pickBest(context.bot().getInventory(), false) != null;
                        if (!hasSafe && FoodRules.pickBest(context.bot().getInventory(), true) != null) {
                            return done(Json.obj("ok", false, "requires_approval", true,
                                    "error", "背包里只有贵重食物，需要主人同意才能吃").toString());
                        }
                    }
                    return plugin.botActions().eat(context.bot(), material, approved);
                });
    }

    private AgentTool giveItem() {
        Schema schema = Schema.object()
                .str("player", "接收物品的玩家名")
                .str("item", "要给的物品名，例如 OAK_LOG；不填则给主手物品")
                .integer("amount", "给多少个，默认全部")
                .require("player");
        return Tools.of("give_item", "直接把背包里的物品放进目标玩家的背包，不会丢在地上。给玩家东西时优先用这个。",
                schema, (context, arguments) -> {
                    Bot bot = context.bot();
                    String playerName = Json.getString(arguments, "player", "").trim();
                    Player target = Bukkit.getPlayerExact(playerName);
                    if (target == null || !target.isOnline()) {
                        return done(Json.error("找不到在线玩家 " + playerName));
                    }
                    if (target.getUniqueId().equals(bot.getUniqueId())) {
                        return done(Json.error("物品已经在我身上了，换一个目标吧"));
                    }

                    String itemName = Json.getString(arguments, "item", "").trim();
                    Material material;
                    if (itemName.isEmpty()) {
                        ItemStack hand = bot.getInventory().getItemInMainHand();
                        if (hand == null || hand.getType().isAir()) {
                            return done(Json.error("主手没有物品，请用 item 参数指定要给的物品"));
                        }
                        material = hand.getType();
                    } else {
                        material = Material.matchMaterial(itemName.toUpperCase(Locale.ROOT));
                        if (material == null || material.isAir()) {
                            return done(Json.error("无效的物品名：" + itemName));
                        }
                    }

                    int wanted = Math.max(1, Json.getInt(arguments, "amount", Integer.MAX_VALUE));
                    int limit = Math.min(36, bot.getInventory().getSize());
                    int given = 0;
                    int remaining = wanted;
                    boolean targetFull = false;

                    for (int slot = 0; slot < limit && remaining > 0; slot++) {
                        ItemStack stack = bot.getInventory().getItem(slot);
                        if (stack == null || stack.getType() != material) {
                            continue;
                        }
                        int take = Math.min(stack.getAmount(), remaining);
                        ItemStack give = stack.clone();
                        give.setAmount(take);

                        Map<Integer, ItemStack> leftover = target.getInventory().addItem(give);
                        int notAdded = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                        int added = take - notAdded;

                        if (added > 0) {
                            ItemStack left = stack.clone();
                            left.setAmount(stack.getAmount() - added);
                            bot.getInventory().setItem(slot, left.getAmount() <= 0 ? null : left);
                            given += added;
                            remaining -= added;
                        }
                        if (notAdded > 0) {
                            targetFull = true;
                            break;
                        }
                    }

                    if (given == 0) {
                        return done(Json.error(targetFull
                                ? target.getName() + " 的背包满了，物品还在我这里"
                                : "背包里没有 " + material.name()));
                    }

                    Text.send(target, "§b" + BotNames.rawName(bot) + " §7给了你 §f" + given + " 个 " + material.name());
                    String message = "已把 " + given + " 个 " + material.name() + " 放进 " + target.getName() + " 的背包";
                    if (targetFull) {
                        message += "；对方背包已满，剩下的还在我这里";
                    } else if (remaining > 0 && given < wanted) {
                        message += "；我身上只有这么多";
                    }
                    return done(Json.ok(message,
                            "item", material.name(),
                            "amount", given,
                            "target", target.getName()));
                });
    }

    private AgentTool pickupItems() {
        Schema schema = Schema.object()
                .integer("radius", "拾取半径，默认 8，最大 16")
                .str("item", "只捡某种物品，例如 OAK_LOG；不填则捡范围內所有掉落物");
        return Tools.of("pickup_items", "走近并捡起附近的掉落物（例如砍下来的原木）。", schema,
                (context, arguments) -> {
                    Bot bot = context.bot();
                    World world = bot.getWorld();
                    if (world == null) {
                        return done(Json.error("我不在任何世界里"));
                    }
                    int radius = Math.max(1, Math.min(16, Json.getInt(arguments, "radius", 8)));
                    String itemName = Json.getString(arguments, "item", "").trim();
                    Set<Material> filter = null;
                    if (!itemName.isEmpty()) {
                        Material material = Material.matchMaterial(itemName.toUpperCase(Locale.ROOT));
                        if (material == null || material.isAir()) {
                            return done(Json.error("无效的物品名：" + itemName));
                        }
                        filter = Set.of(material);
                    }
                    Set<Material> finalFilter = filter;
                    return plugin.botActions().collectItems(bot, bot.getLocation(), radius, finalFilter, 80)
                            .thenApply(picked -> {
                                int remaining = countNearbyItems(bot, radius, finalFilter);
                                String message = picked > 0
                                        ? "已捡起 " + picked + " 堆掉落物"
                                        : "附近没有捡到掉落物";
                                if (remaining > 0) {
                                    message += "，还剩 " + remaining + " 堆";
                                }
                                return Json.ok(message, "picked", picked, "remaining", remaining);
                            });
                });
    }

    private int countNearbyItems(Bot bot, double radius, Set<Material> filter) {
        World world = bot.getWorld();
        if (world == null) {
            return 0;
        }
        int count = 0;
        for (Entity entity : world.getNearbyEntities(bot.getLocation(), radius, Math.max(2.0, radius), radius)) {
            if (!(entity instanceof Item item) || !item.isValid() || item.isDead()) {
                continue;
            }
            if (filter == null || filter.contains(item.getItemStack().getType())) {
                count++;
            }
        }
        return count;
    }

    private AgentTool dropItem() {
        Schema schema = Schema.object()
                .str("item", "要丢弃的物品名，例如 COBBLESTONE")
                .integer("amount", "丢弃数量，默认全部")
                .require("item");
        return Tools.of("drop_item", "把背包里的物品丢到地上（不会丢其他东西）。给玩家物品请用 give_item，不要用这个。",
                schema, (context, arguments) -> {
            Bot bot = context.bot();
            String name = Json.getString(arguments, "item", "").trim().toUpperCase(Locale.ROOT);
            Material material = Material.matchMaterial(name);
            if (material == null) {
                return done(Json.error("无效的物品名：" + name));
            }
            int amount = Json.getInt(arguments, "amount", Integer.MAX_VALUE);
            int remaining = Math.max(1, amount);
            int dropped = 0;

            ItemStack[] contents = bot.getInventory().getContents();
            for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
                ItemStack stack = contents[slot];
                if (stack == null || stack.getType() != material) {
                    continue;
                }
                int move = Math.min(stack.getAmount(), remaining);
                ItemStack drop = stack.clone();
                drop.setAmount(move);
                remaining -= move;
                dropped += move;

                ItemStack left = stack.clone();
                left.setAmount(stack.getAmount() - move);
                bot.getInventory().setItem(slot, left.getAmount() <= 0 ? null : left);
                World world = bot.getWorld();
                if (world != null) {
                    world.dropItemNaturally(bot.getLocation().add(0, 1, 0), drop);
                }
            }
            if (dropped == 0) {
                return done(Json.error("背包里没有 " + name));
            }
            return done(Json.ok("已丢弃物品", "item", name, "amount", dropped));
        });
    }

    private CompletableFuture<String> done(String value) {
        return CompletableFuture.completedFuture(value);
    }
}
