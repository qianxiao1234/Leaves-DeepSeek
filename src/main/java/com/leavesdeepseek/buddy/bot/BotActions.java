package com.leavesdeepseek.buddy.bot;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.config.PluginSettings;
import com.leavesdeepseek.buddy.util.Json;
import com.leavesdeepseek.buddy.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.entity.bot.action.AttackAction;
import org.leavesmc.leaves.entity.bot.action.BotAction;
import org.leavesmc.leaves.entity.bot.action.BreakBlockAction;
import org.leavesmc.leaves.entity.bot.action.JumpAction;
import org.leavesmc.leaves.entity.bot.action.MoveAction;
import org.leavesmc.leaves.entity.bot.action.UseItemAction;
import org.leavesmc.leaves.entity.bot.action.UseItemOffhandAction;
import org.leavesmc.leaves.entity.bot.action.UseItemOnAction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 把常见玩家操作封装成可等待完成的异步动作。
 */
public final class BotActions {

    private static final double MAX_REACH = 4.3;
    /** 眼睛到目标碰撞箱的攻击距离（原作玩家约 3.0，留一点余量避免空砍）。 */
    private static final double ATTACK_REACH = 2.9;
    /** 安全判定频率：每隔多少 tick 检查一次血量/苦力怕/地形。 */
    private static final int SAFETY_INTERVAL_TICKS = 4;
    /** 连续追不到目标的 tick 上限。15 格开外疾跑追过去也要时间，给足 8 秒 */
    private static final int MAX_CHASE_TICKS = 160;
    /** 打远程怪（骷髅等）时：砍完一刀后撤的 tick 数与距离（跑打） */
    private static final int RECOIL_TICKS = 8;
    private static final double RECOIL_DISTANCE = 3.5;
    private static final long COMBAT_TIMEOUT_MS = 30_000L;
    private static final Random RANDOM = new Random();
    private static final List<BlockFace> FACES = List.of(
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN);

    private final LeavesDeepSeekPlugin plugin;
    private final PluginSettings settings;
    private final BotService bots;
    private final BotNavigator navigator;
    private final Map<UUID, Fight> fights = new HashMap<>();
    /** 动作世代：主人每次下达新指令 +1，旧的异步流程看到世代变化会自行退出 */
    private final Map<UUID, Integer> epochs = new java.util.concurrent.ConcurrentHashMap<>();
    /** 正在进食的假人：同一时间只允许一条进食流程，避免手动/自动互相打断 */
    private final Set<UUID> eatingBots = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public BotActions(LeavesDeepSeekPlugin plugin, PluginSettings settings, BotService bots, BotNavigator navigator) {
        this.plugin = plugin;
        this.settings = settings;
        this.bots = bots;
        this.navigator = navigator;
    }

    /**
     * 当前动作世代。
     */
    public int epoch(Bot bot) {
        return bot == null ? 0 : epochs.getOrDefault(bot.getUniqueId(), 0);
    }

    /**
     * 这段异步流程是否还是最新指令（被新指令打断后返回 false）。
     */
    public boolean isCurrent(Bot bot, int epoch) {
        return bot != null && epoch(bot) == epoch;
    }

    /**
     * 打断假人当前所有动作：主人下达新指令时使用，保证「最新命令优先」。
     * 正在等待的挖掘/放置/砍树会立刻返回「已被新指令打断」，不会继续抢移动键。
     */
    public void interrupt(Bot bot, String reason) {
        if (bot == null) {
            return;
        }
        epochs.merge(bot.getUniqueId(), 1, Integer::sum);
        preempt(bot, reason);
    }

    /**
     * 抢占移动/战斗/动作，但**不**改变动作世代。
     * 供 AI 自己在同一次请求里发起的移动指令使用，避免把自己的请求打断。
     */
    public void preempt(Bot bot, String reason) {
        if (bot == null) {
            return;
        }
        stopFight(bot, reason);
        navigator.clearFollowLocks(bot);
        navigator.cancel(bot);
        bots.stopAllActions(bot);
    }

    /**
     * 主人下达「跟着我」但假人正在强制进食：只收掉 AI 的活和战斗，
     * **不碰正在进行的进食动作**，让那口饭先吃完。
     */
    public void yieldToFollow(Bot bot, String reason) {
        if (bot == null) {
            return;
        }
        epochs.merge(bot.getUniqueId(), 1, Integer::sum);
        stopFight(bot, reason);
        navigator.cancel(bot);
        try {
            for (int index = bot.getActionSize() - 1; index >= 0; index--) {
                BotAction<?> action = bot.getAction(index);
                // 吃东西用的是 UseItemAction；保留它，其余动作（挖掘/放置等）立刻收掉
                if (action instanceof UseItemAction
                        || action instanceof UseItemOffhandAction
                        || action instanceof UseItemOnAction) {
                    continue;
                }
                bot.stopAction(index);
            }
        } catch (RuntimeException ignored) {
            // 假人可能已被移除
        }
    }

    /**
     * 进入一段需要独占移动/转向的动作（挖掘、放置、砍树等），期间跟随不会抢方向。
     */
    public void beginJob(Bot bot) {
        navigator.lockFollow(bot);
    }

    public void endJob(Bot bot) {
        navigator.unlockFollow(bot);
    }

    public CompletableFuture<String> breakBlock(Bot bot, Block block) {
        if (!settings.allowBreakBlocks()) {
            return done(Json.error("服务器配置禁止假人破坏方块"));
        }
        if (block == null || block.getType().isAir() || block.isLiquid()) {
            return done(Json.error("目标位置没有可破坏的方块"));
        }
        if (settings.blockedMaterials().contains(block.getType())) {
            return done(Json.error(block.getType().name() + " 被列为受保护方块，不能破坏"));
        }

        int vertical = block.getY() - bot.getLocation().getBlockY();
        if (vertical > 6) {
            return done(unreachable("这块方块在你上方 " + vertical + " 格，够不到，放弃它去处理别的目标吧"));
        }

        Material before = block.getType();
        ItemStack tool = bot.getInventory().getItemInMainHand();
        Set<Material> dropTypes = new HashSet<>();
        int expected = 0;
        for (ItemStack drop : block.getDrops(tool)) {
            if (drop == null || drop.getType().isAir()) {
                continue;
            }
            dropTypes.add(drop.getType());
            expected += drop.getAmount();
        }
        Map<Material, Integer> beforeCounts = countInventory(bot, dropTypes);
        final int expectedTotal = expected;
        final int epoch = epoch(bot);

        beginJob(bot);
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        return approachBlock(bot, block, 400, epoch).thenCompose(arrived -> {
            if (!isCurrent(bot, epoch)) {
                return done(interrupted(before));
            }
            if (!arrived) {
                return done(unreachable("走不过去，够不到这块方块，放弃它吧"));
            }
            return Tasks.delay(plugin, 2).thenCompose(ignored -> {
                if (!isCurrent(bot, epoch)) {
                    return done(interrupted(before));
                }
                Location aim = findVisiblePoint(bot, block);
                if (aim == null) {
                    return done(unreachable("够不到或看不见这块方块，放弃它吧"));
                }
                aimAt(bot, aim);

                BreakBlockAction action = BreakBlockAction.create();
                action.setStartDelayTick(1);
                action.setDoIntervalTick(1);
                action.setDoNumber(1);
                bot.addAction(action);

                return Tasks.poll(plugin, 240, 1,
                                () -> !bot.isValid() || !isCurrent(bot, epoch)
                                        || block.getType() != before || block.getType().isAir())
                        .thenCompose(finished -> {
                            bots.stopActions(bot, BreakBlockAction.class);
                            if (!isCurrent(bot, epoch)) {
                                return done(interrupted(before));
                            }
                            if (!bot.isValid()) {
                                return done(Json.error("假人已离线"));
                            }
                            if (!finished) {
                                return done(Json.error("挖掘超时：可能工具不合适、没有破坏权限，"
                                        + "或服务端关闭了 settings.modify.fakeplayer.use-action"));
                            }

                            CompletableFuture<Void> pickup = CompletableFuture.completedFuture(null);
                            if (settings.miningAutoPickup() && !dropTypes.isEmpty()) {
                                pickup = collectItems(bot, center, settings.miningPickupRadius(), dropTypes, 80)
                                        .thenApply(count -> null);
                            }

                            return pickup.thenApply(pickupIgnored -> {
                                Map<Material, Integer> afterCounts = countInventory(bot, dropTypes);
                                int collected = 0;
                                for (Material type : dropTypes) {
                                    collected += Math.max(0,
                                            afterCounts.getOrDefault(type, 0) - beforeCounts.getOrDefault(type, 0));
                                }
                                int missing = Math.max(0, expectedTotal - collected);

                                String message;
                                if (expectedTotal == 0) {
                                    message = "已破坏 " + before.name() + "，但这次没有掉落物（可能需要正确的工具）";
                                } else if (missing == 0) {
                                    message = "已破坏 " + before.name() + "，掉落物已全部捡起（" + collected + " 个）";
                                } else {
                                    message = "已破坏 " + before.name() + "，只捡到 " + collected + "/" + expectedTotal
                                            + " 个，还有 " + missing + " 个掉在地上，可以调用 pickup_items 继续捡";
                                }
                                return Json.ok(message,
                                        "block", before.name(),
                                        "expected", expectedTotal,
                                        "collected", collected,
                                        "missing", missing);
                            });
                        });
            });
        }).whenComplete((result, error) -> endJob(bot));
    }

    public CompletableFuture<String> placeBlock(Bot bot, Block target, String itemName) {
        if (!settings.allowPlaceBlocks()) {
            return done(Json.error("服务器配置禁止假人放置方块"));
        }
        if (target == null || (!target.getType().isAir() && !target.isLiquid())) {
            return done(Json.error("目标位置已经被占用，无法放置"));
        }

        Material material = resolveMaterial(bot, itemName);
        if (material == null || material.isAir() || !material.isBlock()) {
            return done(Json.error("需要先在手上准备好要放置的方块物品"));
        }
        if (ensureInHand(bot, material) < 0) {
            return done(Json.error("背包里没有 " + material.name()));
        }

        int epoch = epoch(bot);
        beginJob(bot);
        return approachBlock(bot, target, 300, epoch).thenCompose(arrived -> {
            if (!arrived) {
                return done(Json.error("无法靠近放置位置"));
            }
            return Tasks.delay(plugin, 2).thenCompose(ignored -> {
                if (ensureInHand(bot, material) < 0) {
                    return done(Json.error("背包里没有 " + material.name()));
                }
                Location aim = findPlacementAim(bot, target);
                if (aim == null) {
                    return done(Json.error("找不到能放置的支撑面，可能被遮挡或距离太远"));
                }
                aimAt(bot, aim);

                UseItemOnAction action = UseItemOnAction.create();
                action.setStartDelayTick(2);
                action.setDoIntervalTick(2);
                action.setDoNumber(1);
                action.setUseTickTimeout(20);
                bot.addAction(action);

                return Tasks.poll(plugin, 100, 2, () -> !bot.isValid() || !isCurrent(bot, epoch)
                                || target.getType() == material)
                        .thenApply(finished -> {
                            bots.stopActions(bot, UseItemOnAction.class);
                            if (!isCurrent(bot, epoch)) {
                                return Json.error("已被新指令打断，停止放置");
                            }
                            if (!bot.isValid()) {
                                return Json.error("假人已离线");
                            }
                            return finished
                                    ? Json.ok("已放置方块", "block", material.name())
                                    : Json.error("放置失败：可能没有对准、距离太远、位置被占用，或服务端关闭了 settings.modify.fakeplayer.use-action");
                        });
            });
        }).whenComplete((result, error) -> endJob(bot));
    }

    public CompletableFuture<String> useItem(Bot bot, boolean offhand, int useTicks) {
        if (bot != null && isEating(bot)) {
            return done(Json.error("我正在吃东西，等吃完再用物品"));
        }
        ItemStack hand = offhand
                ? bot.getInventory().getItemInOffHand()
                : bot.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            return done(Json.error(offhand ? "副手没有物品" : "主手没有物品"));
        }
        int timeout = Math.max(1, Math.min(useTicks, 200));
        String itemName = hand.getType().name();

        if (offhand) {
            UseItemOffhandAction action = UseItemOffhandAction.create();
            action.setStartDelayTick(1);
            action.setDoIntervalTick(1);
            action.setDoNumber(1);
            action.setUseTickTimeout(timeout);
            bot.addAction(action);
        } else {
            UseItemAction action = UseItemAction.create();
            action.setStartDelayTick(1);
            action.setDoIntervalTick(1);
            action.setDoNumber(1);
            action.setUseTickTimeout(timeout);
            bot.addAction(action);
        }

        return Tasks.delay(plugin, timeout + 15).thenApply(ignored -> {
            bots.stopActions(bot, UseItemAction.class);
            bots.stopActions(bot, UseItemOffhandAction.class);
            return Json.ok("已尝试使用物品", "item", itemName);
        });
    }

    /**
     * 吃背包里的食物。
     *
     * @param requested     指定食物；null 表示自动选择普通食物
     * @param allowValuable 是否允许吃贵重食物（需要主人同意）
     */
    public CompletableFuture<String> eat(Bot bot, Material requested, boolean allowValuable) {
        return eat(bot, requested, allowValuable, false);
    }

    /**
     * @param protectFromEpoch true 表示这次进食是血量急救：主人说「跟着我」只暂停跟随，
     *                         不允许把进食本身打断（跟随会在吃完后自动接上）
     */
    public CompletableFuture<String> eat(Bot bot, Material requested, boolean allowValuable,
                                         boolean protectFromEpoch) {
        if (bot == null || !bot.isValid()) {
            return done(Json.error("假人已离线"));
        }
        if (bot.isDead() || bot.isSleeping() || bots.isDowned(bot)) {
            return done(Json.error("现在不能吃东西（倒地/睡觉/已离线）"));
        }
        Material material = requested;
        if (material == null) {
            material = FoodRules.pickBest(bot.getInventory(), false);
            if (material == null) {
                Material valuable = FoodRules.pickBest(bot.getInventory(), true);
                if (valuable != null && FoodRules.isValuable(valuable)) {
                    return done(Json.obj("ok", false, "requires_approval", true,
                            "error", "背包里只有贵重食物（" + valuable.name() + "），需要主人同意才能吃").toString());
                }
                return done(Json.error("背包里没有可以吃的食物"));
            }
        }
        final Material food = material;
        if (!food.isEdible()) {
            return done(Json.error(food.name() + " 不是食物"));
        }
        if (FoodRules.isValuable(food) && !allowValuable) {
            return done(Json.obj("ok", false, "requires_approval", true,
                    "error", "这是贵重食物（" + food.name() + "），需要主人同意才能吃").toString());
        }
        if (bot.getFoodLevel() >= 20 && !FoodRules.isValuable(food)) {
            return done(Json.error("我现在不饿"));
        }
        UUID botId = bot.getUniqueId();
        if (!eatingBots.add(botId)) {
            return done(Json.error("我正在吃东西，别急"));
        }
        int handSlot;
        try {
            handSlot = ensureInHand(bot, food);
        } catch (RuntimeException exception) {
            eatingBots.remove(botId);
            return done(Json.error("切换手持食物失败：" + Json.rootMessage(exception)));
        }
        if (handSlot < 0) {
            eatingBots.remove(botId);
            return done(Json.error("背包里没有 " + food.name()));
        }

        int foodBefore = bot.getFoodLevel();
        int countBefore = countItems(bot.getInventory(), food);
        final int epoch = epoch(bot);
        try {
            beginJob(bot);
        } catch (RuntimeException exception) {
            eatingBots.remove(botId);
            return done(Json.error("准备进食失败：" + Json.rootMessage(exception)));
        }
        if (plugin.log() != null) {
            plugin.log().info("进食", "开始 | 假人=" + BotNames.rawName(bot) + " | 食物=" + food.name()
                    + " | 血量=" + Math.round(bot.getHealth() * 10) / 10.0
                    + " | 饥饿=" + foodBefore + " | 数量=" + countBefore);
        }
        try {
            UseItemAction action = UseItemAction.create();
            action.setStartDelayTick(1);
            action.setDoIntervalTick(1);
            action.setDoNumber(1);
            action.setUseTickTimeout(40);
            bot.addAction(action);
        } catch (RuntimeException exception) {
            eatingBots.remove(botId);
            endJob(bot);
            plugin.log().warn("进食", "添加使用动作失败：" + Json.rootMessage(exception));
            return done(Json.error("没法把食物送到嘴边：" + Json.rootMessage(exception)));
        }

        return Tasks.poll(plugin, 100, 2, () -> !bot.isValid()
                        || (!protectFromEpoch && !isCurrent(bot, epoch))
                        || bot.getFoodLevel() > foodBefore
                        || countItems(bot.getInventory(), food) < countBefore)
                .thenApply(finished -> {
                    bots.stopActions(bot, UseItemAction.class);
                    if (!bot.isValid()) {
                        return Json.error("假人已离线");
                    }
                    if (!protectFromEpoch && !isCurrent(bot, epoch)) {
                        // 主人下达了新指令（例如「跟着我」）：立刻收手，不要继续嚼
                        return Json.obj("ok", false, "interrupted", true,
                                "error", "吃东西被新指令打断了").toString();
                    }
                    int foodAfter = bot.getFoodLevel();
                    int countAfter = countItems(bot.getInventory(), food);
                    boolean ate = foodAfter > foodBefore || countAfter < countBefore;
                    if (!ate) {
                        return Json.error("没能吃下去：可能还没有饥饿，或手上的物品不适合食用");
                    }
                    return Json.ok("吃掉了一些 " + food.name(),
                            "item", food.name(),
                            "food_before", foodBefore,
                            "food_after", foodAfter,
                            "remaining", countAfter);
                })
                .whenComplete((result, error) -> {
                    eatingBots.remove(botId);
                    endJob(bot);
                    if (plugin.log() != null) {
                        plugin.log().info("进食", "结束 | 假人=" + BotNames.rawName(bot)
                                + " | 结果=" + Json.truncate(String.valueOf(result), 200));
                    }
                });
    }

    /** 是否正在进食（手动 / 自动 / 急救共用）。 */
    public boolean isEating(Bot bot) {
        return bot != null && eatingBots.contains(bot.getUniqueId());
    }

    private int countItems(Inventory inventory, Material material) {
        int count = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /**
     * 自动换上 DPS 最高的武器（耐久低于 10% 的跳过），返回当前手持物品名。
     */
    public String equipBestWeapon(Bot bot) {
        try {
            PlayerInventory inventory = bot.getInventory();
            int slot = WeaponRules.bestSlot(inventory);
            if (slot < 0) {
                return "空手";
            }
            if (slot >= 9) {
                int held = inventory.getHeldItemSlot();
                ItemStack current = inventory.getItem(held);
                ItemStack weapon = inventory.getItem(slot);
                inventory.setItem(held, weapon);
                inventory.setItem(slot, current);
                slot = held;
            }
            if (slot != inventory.getHeldItemSlot()) {
                inventory.setHeldItemSlot(slot);
            }
            ItemStack heldItem = inventory.getItemInMainHand();
            return heldItem == null ? "空手" : heldItem.getType().name();
        } catch (RuntimeException exception) {
            return "未知";
        }
    }

    /**
     * 有盾牌就放到副手（站撸时不主动举盾）。
     */
    public void equipShield(Bot bot) {
        try {
            PlayerInventory inventory = bot.getInventory();
            ItemStack offhand = inventory.getItemInOffHand();
            if (offhand != null && offhand.getType() == Material.SHIELD) {
                return;
            }
            int slot = WeaponRules.shieldSlot(inventory);
            if (slot < 0) {
                return;
            }
            ItemStack shield = inventory.getItem(slot);
            inventory.setItemInOffHand(shield);
            inventory.setItem(slot, offhand);
        } catch (RuntimeException ignored) {
            // 假人可能已离线
        }
    }

    /**
     * 与目标战斗。参考 Wurst7 KillAura / MultiAura 的思路重写：
     * 每 tick 驱动一次，把「位移」和「出刀」彻底解耦——
     * 够不到时只让导航负责赶路，一旦进入攻击距离就立刻停步、持续对准并按武器攻速出刀，
     * 不再出现「等导航结束 → 停一下 → 砍一刀 → 再等导航」的卡顿。
     * 每次出刀会顺带命中攻击距离内最近的敌对生物（MultiAura 简化版）。
     */
    public CompletableFuture<String> attack(Bot bot, LivingEntity target) {
        if (!settings.allowAttack()) {
            return done(Json.error("服务器配置禁止假人攻击"));
        }
        if (target == null || !target.isValid() || target.isDead()) {
            return done(Json.error("目标已经不存在了"));
        }
        if (ThreatRules.isPassive(target.getType())) {
            return done(Json.error("这是中立生物，不主动攻击（避免群体仇恨）"));
        }
        if (ThreatRules.isCreeper(target.getType())) {
            // 苦力怕会爆炸，近战硬砍等于自爆；让上层改成跑开
            return done(Json.obj("ok", false, "retreat", true,
                    "error", "苦力怕会爆炸，我不会去砍它，先离它远一点").toString());
        }
        if (bot == null || !bot.isValid()) {
            return done(Json.error("假人已离线"));
        }
        Fight running = fights.get(bot.getUniqueId());
        if (running != null) {
            return done(Json.error("已经在和 " + running.targetName + " 战斗了"));
        }
        equipShield(bot);

        Fight fight = new Fight(bot, target.getUniqueId(), target.getType().name(),
                System.currentTimeMillis() + COMBAT_TIMEOUT_MS);
        fights.put(bot.getUniqueId(), fight);
        fight.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickFight(fight), 1L, 1L);
        beginJob(bot);
        return fight.future.whenComplete((result, error) -> endJob(bot));
    }

    public boolean isFighting(Bot bot) {
        return bot != null && fights.containsKey(bot.getUniqueId());
    }

    /**
     * 外部打断战斗（主人下达了新指令、假人下线等）。
     */
    public void stopFight(Bot bot, String reason) {
        if (bot == null) {
            return;
        }
        Fight fight = fights.get(bot.getUniqueId());
        if (fight == null) {
            return;
        }
        cancelFight(fight);
        stopCombatMovement(bot);
        bots.stopActions(bot, AttackAction.class);
        fight.future.complete(Json.obj("ok", false, "retreat", false,
                "error", reason == null ? "战斗已中止" : reason).toString());
    }

    public void stopAllFights() {
        for (Fight fight : new ArrayList<>(fights.values())) {
            cancelFight(fight);
            if (fight.bot.isValid()) {
                stopCombatMovement(fight.bot);
                bots.stopActions(fight.bot, AttackAction.class);
            }
            fight.future.complete(Json.obj("ok", false, "retreat", false, "error", "插件正在停用，战斗已中止").toString());
        }
    }

    private void tickFight(Fight fight) {
        try {
            driveFight(fight);
        } catch (Throwable throwable) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "战斗循环异常，已中止这场战斗", throwable);
            cancelFight(fight);
            fight.future.complete(Json.error("战斗出错，已中止：" + throwable.getMessage()));
        }
    }

    private void driveFight(Fight fight) {
        fight.elapsed++;
        Bot bot = fight.bot;
        if (bot == null || !bot.isValid()) {
            endFight(fight, null, Json.error("假人已离线"));
            return;
        }
        LivingEntity focus = asLiving(fight.targetId);
        if (focus == null || !focus.isValid() || focus.isDead()) {
            endFight(fight, bot, Json.ok("目标已被击倒", "target", fight.targetName));
            return;
        }
        if (System.currentTimeMillis() > fight.deadline) {
            endFight(fight, bot, Json.obj("ok", false, "retreat", false,
                    "error", "战斗超时，目标还没倒下", "target", fight.targetName).toString());
            return;
        }
        if (bot.getWorld() == null || !bot.getWorld().equals(focus.getWorld())) {
            endFight(fight, bot, Json.error("目标不在同一世界"));
            return;
        }

        // 安全判定降频到 4 tick 一次，避免在边界条件下反复进退导致抖动
        if (fight.elapsed % SAFETY_INTERVAL_TICKS == 0 && checkSafety(bot, focus, fight)) {
            return;
        }
        if (fight.elapsed % 20 == 1) {
            equipBestWeapon(bot);
        }

        // 进食中：不挥刀，只保持朝向
        if (fight.eatingTicks > 0) {
            fight.eatingTicks--;
            aimAt(bot, center(focus));
            return;
        }

        // 打远程怪（骷髅/掠夺者…）：跑打 —— 砍完一刀先退半步躲开箭，再冲上去
        if (ThreatRules.isRanged(focus.getType()) && fight.recoilTicks > 0) {
            fight.recoilTicks--;
            aimAt(bot, center(focus));
            if (!navigator.isNavigating(bot)) {
                Location back = findSafeSpot(bot, focus, RECOIL_DISTANCE);
                if (back != null) {
                    bot.setSprinting(true);
                    navigator.navigate(bot, back, 0.9, 14);
                }
            }
            return;
        }

        boolean focusInReach = distanceToHitbox(bot, focus) <= ATTACK_REACH;
        LivingEntity victim = pickVictim(bot, focus, focusInReach);
        if (victim == null) {
            chase(bot, focus, fight);
            return;
        }

        // 进入攻击距离：只在状态切换时停一次移动，之后不再反复取消
        if (fight.moving) {
            stopCombatMovement(bot);
            fight.moving = false;
            fight.navGoal = null;
        }
        fight.chaseTicks = 0;

        aimAt(bot, center(victim));
        if (fight.nextAttackIn > 0) {
            fight.nextAttackIn--;
        }
        if (fight.nextAttackIn <= 0) {
            swing(bot, victim);
            fight.nextAttackIn = attackCooldownTicks(bot, victim);
            if (ThreatRules.isRanged(focus.getType())) {
                // 跑打：这一刀之后主动后撤几 tick，避免贴脸被箭击退、也少挨几下
                fight.recoilTicks = RECOIL_TICKS;
            }
        }
    }

    /**
     * 低频安全判定；返回 true 表示这一轮已经结束战斗。
     */
    private boolean checkSafety(Bot bot, LivingEntity focus, Fight fight) {
        if (ThreatRules.isPassive(focus.getType())) {
            retreatAndEnd(fight, bot, focus, 8.0, false, "这是中立生物，不主动攻击，先撤离");
            return true;
        }
        double ratio = bot.getHealth() / Math.max(1.0, bot.getMaxHealth());
        if (ratio < ThreatRules.HEALTH_RETREAT_RATIO) {
            // 紧急进食：附魔金苹果最优先，其次按饱食度从高到低选
            Material food = FoodRules.pickEmergency(bot.getInventory(), bot.getFoodLevel() >= 20);
            // 跑远一点（8 格对僵尸来说等于没跑，跑完立刻又被围住）
            retreatAndEnd(fight, bot, focus, 16.0, false,
                    food == null ? "血量过低，先撤退" : "血量过低，已撤退并吃 " + food.name());
            if (food != null) {
                eat(bot, food, true);
            }
            return true;
        }
        if (ratio < ThreatRules.HEALTH_EAT_RATIO && bot.getFoodLevel() < 18 && fight.eatingTicks <= 0) {
            Material food = FoodRules.pickBest(bot.getInventory(), false);
            if (food != null) {
                fight.eatingTicks = 40;
                eat(bot, food, false);
            }
        }
        if (ThreatRules.isCreeper(focus.getType())) {
            double distance = distanceToHitbox(bot, focus);
            if (distance > ATTACK_REACH && distance <= ThreatRules.CREEPER_DANGER_DISTANCE) {
                retreatAndEnd(fight, bot, focus, ThreatRules.CREEPER_SAFE_DISTANCE + 1.0, false,
                        "苦力怕太近，先拉开距离");
                return true;
            }
        }
        // 战斗途中只要有苦力怕靠近，立刻脱离战斗跑开（爆炸比任何怪都危险）
        LivingEntity nearbyCreeper = nearestCreeper(bot, ThreatRules.CREEPER_DETECT_RADIUS);
        if (nearbyCreeper != null) {
            retreatAndEnd(fight, bot, nearbyCreeper, ThreatRules.CREEPER_FLEE_DISTANCE, false,
                    "附近有苦力怕，先跑开");
            return true;
        }
        if (WeaponRules.bestSlot(bot.getInventory()) < 0) {
            retreatAndEnd(fight, bot, focus, 8.0, false, "没有可用的武器，先撤退");
            return true;
        }
        if (unsafeFooting(bot.getLocation())) {
            Location safe = nearestSafeFooting(bot, 4);
            if (safe != null) {
                navigator.navigate(bot, safe, 1.0, 60);
                fight.moving = true;
                fight.navGoal = safe;
            }
        }
        return false;
    }

    /**
     * 够不到目标时交给导航赶路；这里只下发一次导航，不阻塞等待。
     */
    private void chase(Bot bot, LivingEntity focus, Fight fight) {
        fight.chaseTicks++;
        if (fight.chaseTicks > MAX_CHASE_TICKS) {
            retreatAndEnd(fight, bot, focus, 8.0, false, "追不上目标，先撤退");
            return;
        }
        if (fight.moving && !navigator.isNavigating(bot)) {
            fight.moving = false;
            fight.navGoal = null;
        }
        Location stand = standPoint(bot, focus, Math.max(1.0, ATTACK_REACH - 1.0));
        if (stand == null || stand.getWorld() == null) {
            return;
        }
        // 疾跑追击：走路太慢，15 格外的怪还没到就跑掉了
        if (!bot.isSprinting()) {
            bot.setSprinting(true);
        }
        if (fight.moving && navigator.isNavigating(bot) && fight.navGoal != null
                && fight.navGoal.distanceSquared(stand) <= 4.0) {
            return;
        }
        navigator.navigate(bot, stand, 1.2, 120);
        fight.moving = true;
        fight.navGoal = stand;
    }

    /**
     * 选一个这一刀真正能打到的目标：优先当前目标，否则命中攻击距离内最近的敌对生物。
     */
    private LivingEntity pickVictim(Bot bot, LivingEntity focus, boolean focusInReach) {
        LivingEntity best = focusInReach ? focus : null;
        double bestDistance = focusInReach ? distanceToHitbox(bot, focus) : Double.MAX_VALUE;
        World world = bot.getWorld();
        if (world == null) {
            return best;
        }
        double radius = ATTACK_REACH + 1.0;
        for (Entity entity : world.getNearbyEntities(bot.getLocation(), radius, 3.0, radius)) {
            if (!(entity instanceof LivingEntity living) || !isCombatHostile(living)) {
                continue;
            }
            if (living.getUniqueId().equals(focus.getUniqueId())) {
                continue;
            }
            double distance = distanceToHitbox(bot, living);
            if (distance <= ATTACK_REACH && distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    private boolean isCombatHostile(LivingEntity entity) {
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return false;
        }
        if (entity instanceof Player || !(entity instanceof Enemy)) {
            return false;
        }
        if (ThreatRules.isCreeper(entity.getType())) {
            // 苦力怕绝不主动砍：顺带命中苦力怕也是自杀
            return false;
        }
        if (entity instanceof Tameable tameable && tameable.isTamed()) {
            return false;
        }
        return !ThreatRules.isPassive(entity.getType());
    }

    private LivingEntity nearestCreeper(Bot bot, double radius) {
        World world = bot.getWorld();
        if (world == null) {
            return null;
        }
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : world.getNearbyEntities(bot.getLocation(), radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living)
                    || !ThreatRules.isCreeper(entity.getType())
                    || !living.isValid() || living.isDead()) {
                continue;
            }
            double distance = living.getLocation().distanceSquared(bot.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    private void swing(Bot bot, LivingEntity victim) {
        aimAt(bot, center(victim));
        bots.stopActions(bot, AttackAction.class);
        AttackAction action = AttackAction.create();
        action.setStartDelayTick(1);
        action.setDoIntervalTick(1);
        action.setDoNumber(1);
        bot.addAction(action);
    }

    /**
     * 按手里武器的攻击速度算冷却，并加入 ±1 tick 抖动（对应 Wurst 的攻击速度计时器）。
     */
    private int attackCooldownTicks(Bot bot, LivingEntity victim) {
        int base = WeaponRules.cooldownTicks(bot.getInventory().getItemInMainHand());
        if (ThreatRules.isCreeper(victim.getType())) {
            base = Math.max(base, ThreatRules.ATTACK_COOLDOWN_TICKS);
        }
        return Math.max(4, base + RANDOM.nextInt(3) - 1);
    }

    /**
     * 眼睛到目标碰撞箱的距离（Wurst7 EntityUtils#distanceToHitboxSq 的服务端版本），
     * 比中心点距离更准，能避免「站在 3 格外空砍」。
     */
    private double distanceToHitbox(Bot bot, LivingEntity target) {
        Location eye = bot.getEyeLocation();
        BoundingBox box = target.getBoundingBox();
        double x = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double y = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double z = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        double dx = eye.getX() - x;
        double dy = eye.getY() - y;
        double dz = eye.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Location center(LivingEntity entity) {
        BoundingBox box = entity.getBoundingBox();
        return new Location(entity.getWorld(),
                (box.getMinX() + box.getMaxX()) / 2.0,
                (box.getMinY() + box.getMaxY()) / 2.0,
                (box.getMinZ() + box.getMaxZ()) / 2.0);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private LivingEntity asLiving(UUID id) {
        Entity entity = Bukkit.getEntity(id);
        return entity instanceof LivingEntity living ? living : null;
    }

    private void retreatAndEnd(Fight fight, Bot bot, LivingEntity threat, double distance,
                               boolean sneak, String message) {
        String targetName = threat == null ? fight.targetName : threat.getType().name();
        cancelFight(fight);
        stopCombatMovement(bot);
        bots.stopActions(bot, AttackAction.class);
        retreatFrom(bot, threat, distance, sneak).whenComplete((arrived, error) -> {
            if (error != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "战斗撤退时出错", error);
            }
            fight.future.complete(Json.obj("ok", false, "retreat", true, "error", message,
                    "target", targetName).toString());
        });
    }

    private void endFight(Fight fight, Bot bot, String result) {
        cancelFight(fight);
        if (bot != null && bot.isValid()) {
            stopCombatMovement(bot);
            bots.stopActions(bot, AttackAction.class);
        }
        fight.future.complete(result);
    }

    private void cancelFight(Fight fight) {
        fights.remove(fight.botId());
        if (fight.task != null) {
            fight.task.cancel();
            fight.task = null;
        }
    }

    /**
     * 一场持续战斗的运行时状态。
     */
    private static final class Fight {

        private final Bot bot;
        private final UUID botId;
        private final UUID targetId;
        private final String targetName;
        private final long deadline;
        private final CompletableFuture<String> future = new CompletableFuture<>();

        private BukkitTask task;
        private int elapsed;
        private int nextAttackIn;
        private int chaseTicks;
        private int eatingTicks;
        /** 打远程怪时的「后撤」剩余 tick（跑打：砍一刀退半步再冲） */
        private int recoilTicks;
        private boolean moving;
        private Location navGoal;

        private Fight(Bot bot, UUID targetId, String targetName, long deadline) {
            this.bot = bot;
            this.botId = bot.getUniqueId();
            this.targetId = targetId;
            this.targetName = targetName;
            this.deadline = deadline;
        }

        private UUID botId() {
            return botId;
        }
    }

    /**
     * 停止移动类输入，确保不会边跑边砍。
     */
    private void stopCombatMovement(Bot bot) {
        navigator.cancel(bot);
        bots.stopActions(bot, MoveAction.class);
        bots.stopActions(bot, JumpAction.class);
        bot.setSprinting(false);
    }

    /**
     * 站在目标外侧约 range 格的位置，而不是直接走进目标身体里。
     */
    private Location standPoint(Bot bot, LivingEntity target, double range) {
        Vector away = bot.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.01) {
            away = new Vector(1, 0, 0);
        }
        away.normalize();
        Location stand = target.getLocation().clone().add(away.multiply(range));
        stand.setY(bot.getLocation().getY());
        return stand;
    }

    /**
     * 紧急浮上水面：在水下时持续给一个向上的速度，直到头部离开水面或超时。
     * <p>注意不能用 JumpAction：它在水下 {@code onGround()} 为 false，根本不会起跳。</p>
     */
    public CompletableFuture<Boolean> surface(Bot bot) {
        return waterBoost(bot, null, 120);
    }

    /**
     * 从水里爬上岸：朝岸边给「向上 + 向前」的速度，帮助假人越过 1 格高的岸沿。
     *
     * @param shore 岸边落脚点
     */
    public CompletableFuture<Boolean> climbOutOfWater(Bot bot, Location shore) {
        return waterBoost(bot, shore, 80);
    }

    /**
     * 水域位移辅助：每 2 tick 覆盖一次水平/垂直速度。
     *
     * @param shore 上岸方向；null 表示只向上浮
     */
    private CompletableFuture<Boolean> waterBoost(Bot bot, Location shore, int maxTicks) {
        if (bot == null || !bot.isValid()) {
            return CompletableFuture.completedFuture(false);
        }
        if (!bot.isInWater() && !bot.isUnderWater()) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        int[] elapsed = {0};
        BukkitTask[] taskRef = new BukkitTask[1];
        Vector direction = new Vector(0, 0, 0);
        if (shore != null) {
            direction = shore.toVector().subtract(bot.getLocation().toVector()).setY(0);
            if (direction.lengthSquared() > 0.0001) {
                direction.normalize();
            }
        }
        final Vector horizontal = direction;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            boolean finished;
            Boolean done = null;
            try {
                elapsed[0] += 2;
                if (!bot.isValid()) {
                    done = false;
                } else if (shore == null ? !bot.isUnderWater() : !bot.isInWater()) {
                    done = true;
                } else if (elapsed[0] >= maxTicks) {
                    done = false;
                } else {
                    Vector velocity = bot.getVelocity();
                    double y = Math.max(0.32, Math.min(0.45, velocity.getY()));
                    bot.setVelocity(new Vector(horizontal.getX() * 0.20, y, horizontal.getZ() * 0.20));
                }
            } catch (Throwable throwable) {
                done = false;
            }
            finished = done != null;
            if (finished) {
                future.complete(done);
            }
        }, 0L, 2L);
        taskRef[0] = task;
        future.whenComplete((result, error) -> {
            if (taskRef[0] != null) {
                taskRef[0].cancel();
            }
            if (bot.isValid()) {
                bot.setFallDistance(0.0f);
            }
        });
        return future;
    }

    /**
     * 远离威胁；sneak=true 时全程潜行（用于坚守者）。
     * <p>用 {@code PRIORITY_HIGH}：跟随是 HIGH 时 {@code claim()} 会挡住普通优先级，
     * 之前「跟着主人时撤退一秒就结束、然后原地发呆」就是这么来的。</p>
     */
    public CompletableFuture<Boolean> retreatFrom(Bot bot, LivingEntity threat, double distance, boolean sneak) {
        if (bot == null || !bot.isValid()) {
            return CompletableFuture.completedFuture(false);
        }
        Location safe = findSafeSpot(bot, threat, Math.max(4.0, distance));
        if (safe == null) {
            return CompletableFuture.completedFuture(false);
        }
        bot.setSprinting(!sneak);
        if (sneak) {
            bot.setSneaking(true);
        }
        // 撤退途中别被跟随看门狗拉回去（持续撤退的调用方会自己反复延长这个暂停）
        navigator.suspendFollow(bot, 8000L);
        return navigator.navigateAndWait(bot, safe, 1.5, 240, BotNavigator.PRIORITY_HIGH)
                .whenComplete((arrived, error) -> Tasks.onMain(plugin, () -> {
                    if (bot.isValid()) {
                        bot.setSprinting(false);
                        if (sneak) {
                            bot.setSneaking(false);
                        }
                    }
                    return null;
                }));
    }

    private Location findSafeSpot(Bot bot, LivingEntity threat, double distance) {
        World world = bot.getWorld();
        if (world == null) {
            return null;
        }
        Location origin = bot.getLocation();
        Vector away = origin.toVector().subtract(threat.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.01) {
            away = new Vector(1, 0, 0);
        }
        away.normalize();
        double[] angles = {0, 30, -30, 60, -60, 90, -90, 135, -135, 180};
        for (double angle : angles) {
            Vector direction = away.clone().rotateAroundY(Math.toRadians(angle));
            Location base = origin.clone().add(direction.multiply(distance));
            Location ground = findGround(world, base);
            if (ground != null) {
                return ground;
            }
        }
        return origin.clone().add(away.multiply(distance));
    }

    private Location findGround(World world, Location base) {
        for (int dy = 3; dy >= -4; dy--) {
            Block feet = world.getBlockAt(base.getBlockX(), base.getBlockY() + dy, base.getBlockZ());
            Block head = feet.getRelative(BlockFace.UP);
            Block below = feet.getRelative(BlockFace.DOWN);
            if (feet.isPassable() && head.isPassable() && !below.isPassable() && below.getType().isSolid()) {
                return new Location(world, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            }
        }
        return null;
    }

    /**
     * 站在边缘、水边、岩浆边时不适合站撸。
     */
    private boolean unsafeFooting(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return true;
        }
        Block feet = location.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block below = feet.getRelative(BlockFace.DOWN);
        if (feet.isLiquid() || head.isLiquid() || below.isLiquid()) {
            return true;
        }
        Material belowType = below.getType();
        if (belowType == Material.MAGMA_BLOCK || belowType == Material.CAMPFIRE || belowType == Material.LAVA) {
            return true;
        }
        int dropEdges = 0;
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block side = feet.getRelative(face);
            Block sideBelow = side.getRelative(BlockFace.DOWN);
            if (side.isPassable() && sideBelow.isPassable()) {
                dropEdges++;
            }
        }
        if (dropEdges >= 3) {
            return true;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Block block = world.getBlockAt(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (block.getType() == Material.LAVA) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Location nearestSafeFooting(Bot bot, int radius) {
        World world = bot.getWorld();
        if (world == null) {
            return null;
        }
        Location origin = bot.getLocation();
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        Block feet = world.getBlockAt(origin.getBlockX() + dx,
                                origin.getBlockY() + dy, origin.getBlockZ() + dz);
                        Block head = feet.getRelative(BlockFace.UP);
                        Block below = feet.getRelative(BlockFace.DOWN);
                        if (!feet.isPassable() || !head.isPassable() || !below.getType().isSolid()) {
                            continue;
                        }
                        Location candidate = new Location(world, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                        if (!unsafeFooting(candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 找到最近的床（只取床头）。
     */
    public Block findNearestBed(Bot bot, int radius) {
        World world = bot.getWorld();
        if (world == null) {
            return null;
        }
        Location location = bot.getLocation();
        int r = Math.max(2, Math.min(32, radius));
        Block best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    Block block = world.getBlockAt(location.getBlockX() + dx,
                            location.getBlockY() + dy, location.getBlockZ() + dz);
                    if (!(block.getBlockData() instanceof Bed bed) || bed.getPart() != Bed.Part.HEAD) {
                        continue;
                    }
                    double distance = block.getLocation().distanceSquared(location);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = block;
                    }
                }
            }
        }
        return best;
    }

    /**
     * 走到床边并尝试睡觉。
     */
    public CompletableFuture<String> sleepInBed(Bot bot, Block bed) {
        if (bed == null || !(bed.getBlockData() instanceof Bed bedData)) {
            return done(Json.error("这里没有床"));
        }
        if (bedData.isOccupied()) {
            return done(Json.error("这张床已经有人在睡了"));
        }
        World world = bed.getWorld();
        boolean night = !world.isDayTime() || world.isThundering();
        String position = bed.getX() + ", " + bed.getY() + ", " + bed.getZ();

        Location stand = findStandSpot(bed);
        beginJob(bot);
        CompletableFuture<Boolean> walk = stand == null
                ? CompletableFuture.completedFuture(true)
                : navigator.navigateAndWait(bot, stand, 0.8, 120);

        return walk.thenCompose(arrived -> {
            if (!arrived) {
                return done(Json.error("走不到床边，可能被挡住了"));
            }
            boolean started;
            try {
                started = bot.sleep(bed.getLocation().add(0.5, 0.5, 0.5), true);
            } catch (Throwable throwable) {
                started = false;
            }
            if (!started) {
                return done(Json.error("服务器拒绝了睡觉请求" + (night ? "" : "（现在不是夜晚/雷暴）")
                        + "。假人已设置为不参与睡眠计数，不会影响玩家跳过夜晚。"));
            }
            return Tasks.poll(plugin, 100, 2, () -> !bot.isValid() || bot.isSleeping())
                    .thenApply(sleeping -> {
                        if (!bot.isValid()) {
                            return Json.error("假人已离线");
                        }
                        return sleeping
                                ? Json.ok("已经躺在床上睡觉了", "bed", position)
                                : Json.error("躺下了但没有进入睡眠，可能床被阻挡或附近有怪物");
                    });
        }).whenComplete((result, error) -> endJob(bot));
    }

    public boolean wakeUp(Bot bot) {
        if (bot != null && bot.isSleeping()) {
            bot.wakeup(false);
            return true;
        }
        return false;
    }

    private Location findStandSpot(Block bed) {
        World world = bed.getWorld();
        int bx = bed.getX();
        int by = bed.getY();
        int bz = bed.getZ();
        for (int ring = 1; ring <= 3; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        Block feet = world.getBlockAt(bx + dx, by + dy, bz + dz);
                        Block head = world.getBlockAt(bx + dx, by + dy + 1, bz + dz);
                        Block below = world.getBlockAt(bx + dx, by + dy - 1, bz + dz);
                        if (!feet.isPassable() || !head.isPassable() || !below.getType().isSolid()) {
                            continue;
                        }
                        return new Location(world, bx + dx + 0.5, by + dy, bz + dz + 0.5);
                    }
                }
            }
        }
        return null;
    }

    /**
     * 走到「能看见并且够得到」目标方块的位置。
     * <p>已经能看见时立刻返回，不再无意义地绕路；看不见时在方块周围（含上一层/下一层）
     * 依次尝试几个落脚点，所以树长在坡上、被树叶挡住一半时也能找到合适站位。</p>
     */
    public CompletableFuture<Boolean> approachBlock(Bot bot, Block block, int timeoutTicks) {
        return approachBlock(bot, block, timeoutTicks, epoch(bot));
    }

    public CompletableFuture<Boolean> approachBlock(Bot bot, Block block, int timeoutTicks, int epoch) {
        if (bot == null || block == null || bot.getWorld() == null
                || !bot.getWorld().equals(block.getWorld())) {
            return CompletableFuture.completedFuture(false);
        }
        if (findVisiblePoint(bot, block) != null) {
            return CompletableFuture.completedFuture(true);
        }
        List<Location> candidates = standingSpots(bot, block);
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return tryStandingSpot(bot, block, candidates, 0, timeoutTicks, epoch);
    }

    private CompletableFuture<Boolean> tryStandingSpot(Bot bot, Block block, List<Location> candidates,
                                                       int index, int timeoutTicks, int epoch) {
        if (index >= candidates.size() || !isCurrent(bot, epoch) || !bot.isValid()) {
            return CompletableFuture.completedFuture(false);
        }
        Location stand = candidates.get(index);
        double horizontal = Math.hypot(stand.getX() - bot.getX(), stand.getZ() - bot.getZ());
        if (horizontal < 0.85 && Math.abs(stand.getY() - bot.getY()) < 1.2) {
            if (findVisiblePoint(bot, block) != null) {
                return CompletableFuture.completedFuture(true);
            }
            return tryStandingSpot(bot, block, candidates, index + 1, timeoutTicks, epoch);
        }
        int ticks = Math.min(timeoutTicks, Math.max(60, (int) (bot.getLocation().distance(stand) * 24)));
        return navigator.navigateAndWait(bot, stand, 0.9, ticks)
                .thenCompose(arrived -> {
                    if (!arrived) {
                        return CompletableFuture.completedFuture(false);
                    }
                    if (findVisiblePoint(bot, block) != null) {
                        return CompletableFuture.completedFuture(true);
                    }
                    return tryStandingSpot(bot, block, candidates, index + 1, timeoutTicks, epoch);
                });
    }

    /**
     * 方块周围能站人的位置，按离假人远近排序（最多 6 个）。
     */
    private List<Location> standingSpots(Bot bot, Block block) {
        World world = block.getWorld();
        List<Location> spots = new ArrayList<>();
        if (world == null) {
            return spots;
        }
        Location blockCenter = block.getLocation().add(0.5, 0.5, 0.5);
        double reach = MAX_REACH - 0.35;
        for (int ring = 1; ring <= 3; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        Block feet = world.getBlockAt(block.getX() + dx, block.getY() + dy, block.getZ() + dz);
                        Block head = feet.getRelative(BlockFace.UP);
                        Block below = feet.getRelative(BlockFace.DOWN);
                        if (!feet.isPassable() || !head.isPassable() || feet.isLiquid()) {
                            continue;
                        }
                        if (below.isLiquid() || !below.getType().isSolid()) {
                            continue;
                        }
                        Location spot = new Location(world, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                        Location eye = spot.clone().add(0, 1.62, 0);
                        if (eye.distanceSquared(blockCenter) > reach * reach) {
                            continue;
                        }
                        spots.add(spot);
                    }
                }
            }
        }
        spots.sort(Comparator.comparingDouble(spot -> spot.distanceSquared(bot.getLocation())));
        return spots.size() > 6 ? new ArrayList<>(spots.subList(0, 6)) : spots;
    }

    /**
     * 砍掉一整棵树：一次性走到树干旁，自下而上一段段挖掉，最后统一捡回掉落物。
     * <p>比让模型逐块调用 mine_block 快很多，也不会中途跑去拆别的方块。</p>
     */
    public CompletableFuture<String> chopTree(Bot bot, Block anyLog, int maxLogs) {
        if (!settings.allowBreakBlocks()) {
            return done(Json.error("服务器配置禁止假人破坏方块"));
        }
        if (bot == null || !bot.isValid()) {
            return done(Json.error("假人已离线"));
        }
        World world = bot.getWorld();
        if (world == null || anyLog == null || !world.equals(anyLog.getWorld())
                || !TreeGuard.isLog(anyLog.getType())) {
            return done(Json.error("那里没有可砍的原木"));
        }
        if (settings.requireLeavesForLogs() && !TreeGuard.isTreeLog(world, anyLog, settings.logLeafRadius())) {
            return done(Json.error("这块原木不是自然生长的树：树干底部不是泥土/草方块，"
                    + "或者树顶周围没有树叶，看起来是村民房子或玩家建筑，我不会拆。请换一棵真正的树。"));
        }
        List<Block> logs = TreeGuard.treeBlocks(world, anyLog, Math.max(1, maxLogs));
        if (logs.isEmpty()) {
            return done(Json.error("没有找到可砍的原木"));
        }
        logs.sort(Comparator.comparingInt(Block::getY)
                .thenComparingDouble(block -> block.getLocation().distanceSquared(bot.getLocation())));
        Material wood = logs.get(0).getType();
        Location center = anyLog.getLocation().add(0.5, 0.5, 0.5);
        int epoch = epoch(bot);
        beginJob(bot);
        int before = countItems(bot.getInventory(), wood);
        int[] stats = new int[2];

        return chopStep(bot, logs, 0, epoch, stats)
                .thenCompose(ignored -> {
                    if (!isCurrent(bot, epoch)) {
                        return done(Json.obj("ok", false, "interrupted", true,
                                "error", "已被新指令打断，停止砍树").toString());
                    }
                    if (!settings.miningAutoPickup()) {
                        return done(Json.ok("砍完这棵树：挖掉 " + stats[0] + " 段原木",
                                "block", wood.name(), "broken", stats[0], "failed", stats[1]));
                    }
                    return collectItems(bot, center, settings.miningPickupRadius() + 4, null, 120)
                            .thenApply(picked -> {
                                int collected = Math.max(0, countItems(bot.getInventory(), wood) - before);
                                String message = "砍完这棵树：挖掉 " + stats[0] + " 段原木，捡到 " + collected + " 个"
                                        + (stats[1] > 0 ? "（还有 " + stats[1] + " 段够不到）" : "");
                                return Json.ok(message,
                                        "block", wood.name(),
                                        "broken", stats[0],
                                        "failed", stats[1],
                                        "collected", collected,
                                        "picked_up", picked);
                            });
                })
                .whenComplete((result, error) -> endJob(bot));
    }

    private CompletableFuture<Void> chopStep(Bot bot, List<Block> logs, int index, int epoch, int[] stats) {
        if (index >= logs.size() || !bot.isValid() || !isCurrent(bot, epoch)) {
            return CompletableFuture.completedFuture(null);
        }
        Block block = logs.get(index);
        if (!TreeGuard.isLog(block.getType())) {
            return chopStep(bot, logs, index + 1, epoch, stats);
        }
        return digDirect(bot, block, epoch, 200).thenCompose(broken -> {
            if (broken) {
                stats[0]++;
            } else {
                stats[1]++;
            }
            return chopStep(bot, logs, index + 1, epoch, stats);
        });
    }

    /**
     * 挖掉指定方块（供砍树循环复用，不重复统计掉落物）。
     */
    private CompletableFuture<Boolean> digDirect(Bot bot, Block block, int epoch, int timeoutTicks) {
        if (!isCurrent(bot, epoch) || !bot.isValid()) {
            return CompletableFuture.completedFuture(false);
        }
        Material before = block.getType();
        if (before.isAir()) {
            return CompletableFuture.completedFuture(true);
        }
        return approachBlock(bot, block, timeoutTicks, epoch).thenCompose(arrived -> {
            if (!arrived || !isCurrent(bot, epoch) || !bot.isValid()) {
                return CompletableFuture.completedFuture(false);
            }
            Location aim = findVisiblePoint(bot, block);
            if (aim == null) {
                return CompletableFuture.completedFuture(false);
            }
            aimAt(bot, aim);

            BreakBlockAction action = BreakBlockAction.create();
            action.setStartDelayTick(1);
            action.setDoIntervalTick(1);
            action.setDoNumber(1);
            bot.addAction(action);

            return Tasks.poll(plugin, 200, 1, () -> !bot.isValid() || !isCurrent(bot, epoch)
                            || block.getType() != before || block.getType().isAir())
                    .thenApply(finished -> {
                        bots.stopActions(bot, BreakBlockAction.class);
                        return finished && isCurrent(bot, epoch) && block.getType() != before;
                    });
        });
    }

    private String interrupted(Material block) {
        return Json.obj("ok", false, "interrupted", true,
                "error", "已被主人的新指令打断，停止挖掘 " + block.name()).toString();
    }

    public CompletableFuture<Boolean> approach(Bot bot, Location point, double range, int timeoutTicks) {
        if (bot.getWorld() == null || point.getWorld() == null || !bot.getWorld().equals(point.getWorld())) {
            return CompletableFuture.completedFuture(false);
        }
        if (bot.getEyeLocation().distanceSquared(point) <= range * range) {
            return CompletableFuture.completedFuture(true);
        }
        double distance = bot.getLocation().distance(point);
        int ticks = Math.min(timeoutTicks, Math.max(60, (int) (distance * 22)));
        return navigator.navigateAndWait(bot, point, 1.5, ticks);
    }

    public void aimAt(Bot bot, Location point) {
        Location eye = bot.getEyeLocation();
        double dx = point.getX() - eye.getX();
        double dy = point.getY() - eye.getY();
        double dz = point.getZ() - eye.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        bot.setRotation(yaw, pitch);
    }

    public Location findVisiblePoint(Bot bot, Block block) {
        Location eye = bot.getEyeLocation();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        List<Location> candidates = new ArrayList<>();
        candidates.add(center);
        for (BlockFace face : FACES) {
            Vector normal = face.getDirection();
            candidates.add(center.clone().add(normal.getX() * 0.49, normal.getY() * 0.49, normal.getZ() * 0.49));
        }
        for (Location candidate : candidates) {
            if (eye.distanceSquared(candidate) > MAX_REACH * MAX_REACH) {
                continue;
            }
            RayTraceResult result = rayTrace(bot, candidate);
            if (result != null && block.equals(result.getHitBlock())) {
                return candidate;
            }
        }
        return null;
    }

    private Location findPlacementAim(Bot bot, Block target) {
        Location eye = bot.getEyeLocation();
        for (BlockFace face : FACES) {
            Block support = target.getRelative(face);
            if (!support.getType().isSolid()) {
                continue;
            }
            Vector normal = face.getDirection();
            Location aim = support.getLocation().add(
                    0.5 - normal.getX() * 0.5,
                    0.5 - normal.getY() * 0.5,
                    0.5 - normal.getZ() * 0.5);
            if (eye.distanceSquared(aim) > MAX_REACH * MAX_REACH) {
                continue;
            }
            RayTraceResult result = rayTrace(bot, aim);
            if (result != null && support.equals(result.getHitBlock())) {
                return aim;
            }
        }
        return null;
    }

    private RayTraceResult rayTrace(Bot bot, Location point) {
        Location eye = bot.getEyeLocation();
        Vector direction = point.toVector().subtract(eye.toVector());
        double length = direction.length();
        if (length < 0.05 || length > MAX_REACH + 0.5) {
            return null;
        }
        return bot.getWorld().rayTraceBlocks(eye, direction.multiply(1.0 / length), length + 0.05,
                FluidCollisionMode.NEVER, true);
    }

    public Material resolveMaterial(Bot bot, String itemName) {
        if (itemName != null && !itemName.isBlank()) {
            try {
                return Material.matchMaterial(itemName.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException exception) {
                return null;
            }
        }
        ItemStack hand = bot.getInventory().getItemInMainHand();
        return hand == null || hand.getType().isAir() ? null : hand.getType();
    }

    /**
     * 把指定材料放到快捷栏并选中，返回槽位；找不到返回 -1。
     */
    public int ensureInHand(Bot bot, Material material) {
        PlayerInventory inventory = bot.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        if (hand != null && hand.getType() == material) {
            return inventory.getHeldItemSlot();
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && stack.getType() == material) {
                inventory.setHeldItemSlot(slot);
                return slot;
            }
        }
        for (int slot = 9; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int targetSlot = firstEmptyHotbar(inventory);
            if (targetSlot < 0) {
                targetSlot = inventory.getHeldItemSlot();
            }
            ItemStack old = inventory.getItem(targetSlot);
            inventory.setItem(targetSlot, stack);
            inventory.setItem(slot, old);
            inventory.setHeldItemSlot(targetSlot);
            return targetSlot;
        }
        return -1;
    }

    private int firstEmptyHotbar(PlayerInventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * 走近并捡起附近的掉落物，返回捡到的掉落物堆数。
     */
    public CompletableFuture<Integer> collectItems(Bot bot, Location center, double radius,
                                                   Set<Material> filter, int timeoutTicks) {
        if (bot.getWorld() == null || center == null || center.getWorld() == null
                || !bot.getWorld().equals(center.getWorld())) {
            return CompletableFuture.completedFuture(0);
        }
        double r = Math.max(1.0, radius);
        List<Item> items = new ArrayList<>();
        for (Entity entity : bot.getWorld().getNearbyEntities(center, r, Math.max(2.0, r), r)) {
            if (!(entity instanceof Item item) || !item.isValid() || item.isDead()) {
                continue;
            }
            if (filter != null && !filter.contains(item.getItemStack().getType())) {
                continue;
            }
            items.add(item);
        }
        items.sort(Comparator.comparingDouble(item -> item.getLocation().distanceSquared(bot.getLocation())));
        if (items.size() > 8) {
            items = new ArrayList<>(items.subList(0, 8));
        }
        beginJob(bot);
        return collectSequentially(bot, items, 0, 0, Math.max(30, Math.min(120, timeoutTicks)))
                .whenComplete((result, error) -> endJob(bot));
    }

    private CompletableFuture<Integer> collectSequentially(Bot bot, List<Item> items, int index,
                                                           int collected, int timeoutTicks) {
        if (index >= items.size() || !bot.isValid()) {
            return CompletableFuture.completedFuture(collected);
        }
        Item item = items.get(index);
        if (!item.isValid() || item.isDead()) {
            return collectSequentially(bot, items, index + 1, collected, timeoutTicks);
        }
        return navigator.navigateAndWait(bot, item.getLocation(), 0.8, timeoutTicks)
                .thenCompose(arrived -> {
                    if (!arrived) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return Tasks.poll(plugin, 25, 1, () -> !item.isValid() || item.isDead());
                })
                .thenCompose(picked -> collectSequentially(bot, items, index + 1,
                        picked ? collected + 1 : collected, timeoutTicks));
    }

    private Map<Material, Integer> countInventory(Bot bot, Set<Material> types) {
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack stack : bot.getInventory().getContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            if (types != null && !types.contains(stack.getType())) {
                continue;
            }
            counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
        return counts;
    }

    /**
     * 返回带有 unreachable 标记的失败结果，提示模型换目标而不是反复重试。
     */
    private String unreachable(String message) {
        return Json.obj("ok", false, "unreachable", true, "error", message).toString();
    }

    private CompletableFuture<String> done(String value) {
        return CompletableFuture.completedFuture(value);
    }
}
