package com.leavesdeepseek.buddy.tools;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.ai.AgentTool;
import com.leavesdeepseek.buddy.ai.Schema;
import com.leavesdeepseek.buddy.ai.Tools;
import com.leavesdeepseek.buddy.bot.TreeGuard;
import com.leavesdeepseek.buddy.util.Json;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 方块相关工具。
 */
public final class BlockTools {

    private final LeavesDeepSeekPlugin plugin;

    public BlockTools(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public List<AgentTool> all() {
        return List.of(mineBlock(), chopTree(), placeBlock(), findBlock(), inspectBlock());
    }

    private AgentTool chopTree() {
        Schema schema = Schema.object()
                .number("x", "目标树任意一段原木的 X 坐标；不填则自动找附近最近的树")
                .number("y", "目标树任意一段原木的 Y 坐标")
                .number("z", "目标树任意一段原木的 Z 坐标")
                .integer("radius", "自动找树时的搜索半径，默认 16，最大 24")
                .integer("max_logs", "最多砍多少段原木，默认 64");
        return Tools.of("chop_tree",
                "砍掉一整棵树：自动走到树干旁、自下而上把整棵树的原木全部挖掉，并捡回掉落物。"
                        + "不填坐标时自动寻找附近最近的、带树叶的真树。"
                        + "村民房子、玩家建筑上的原木会被拒绝，不会误拆。",
                schema, (context, arguments) -> {
                    Bot bot = context.bot();
                    World world = bot.getWorld();
                    if (world == null) {
                        return done(Json.error("我不在任何世界里"));
                    }
                    Block target = null;
                    double x = Json.getDouble(arguments, "x", Double.NaN);
                    double y = Json.getDouble(arguments, "y", Double.NaN);
                    double z = Json.getDouble(arguments, "z", Double.NaN);
                    if (!Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z)) {
                        target = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                    }
                    if (target == null || !TreeGuard.isLog(target.getType())) {
                        int radius = Math.max(4, Math.min(24, Json.getInt(arguments, "radius", 16)));
                        target = TreeGuard.findNearestTree(world, bot.getLocation(), radius);
                        if (target == null) {
                            return done(Json.error("附近 " + radius + " 格内没有找到自然生长的树。"
                                    + "可以用 find_block 看看周围的树在哪。"));
                        }
                    }
                    String rangeError = ToolGuards.checkRange(plugin.settings(), context.owner(),
                            target.getLocation().add(0.5, 0.5, 0.5));
                    if (rangeError != null) {
                        return done(Json.error(rangeError));
                    }
                    int maxLogs = Math.max(1, Math.min(128, Json.getInt(arguments, "max_logs", 64)));
                    return plugin.botActions().chopTree(bot, target, maxLogs);
                });
    }

    private AgentTool mineBlock() {
        Schema schema = Schema.object()
                .number("x", "方块 X 坐标")
                .number("y", "方块 Y 坐标")
                .number("z", "方块 Z 坐标")
                .require("x", "y", "z");
        return Tools.of("mine_block", "挖掘指定坐标的方块。会自动走近并面向方块；挖完自动捡起掉落物并核对数量。", schema, (context, arguments) -> {
            Bot bot = context.bot();
            Block block = resolveBlock(bot, arguments);
            if (block == null) {
                return done(Json.error("坐标无效或目标不在当前世界"));
            }
            if (block.getType().isAir() || block.isLiquid()) {
                return done(Json.error("那里没有可挖掘的方块"));
            }
            if (TreeGuard.isLog(block.getType()) && plugin.settings().requireLeavesForLogs()
                    && !TreeGuard.isTreeLog(block.getWorld(), block, plugin.settings().logLeafRadius())) {
                return done(Json.error("这块原木附近没有树叶，应该是村民房子或其他建筑的木头，我不会拆。"
                        + "请去找带树叶的树。"));
            }
            String rangeError = ToolGuards.checkRange(plugin.settings(), context.owner(),
                    block.getLocation().add(0.5, 0.5, 0.5));
            if (rangeError != null) {
                return done(Json.error(rangeError));
            }
            return plugin.botActions().breakBlock(bot, block);
        });
    }

    private AgentTool placeBlock() {
        Schema schema = Schema.object()
                .number("x", "放置位置的 X 坐标")
                .number("y", "放置位置的 Y 坐标")
                .number("z", "放置位置的 Z 坐标")
                .str("item", "要放置的方块物品名，例如 STONE；不填则使用主手物品")
                .require("x", "y", "z");
        return Tools.of("place_block", "在指定坐标放置方块。会自动走近、切换物品并对准支撑面。", schema,
                (context, arguments) -> {
                    Bot bot = context.bot();
                    Block target = resolveBlock(bot, arguments);
                    if (target == null) {
                        return done(Json.error("坐标无效或目标不在当前世界"));
                    }
                    String rangeError = ToolGuards.checkRange(plugin.settings(), context.owner(),
                            target.getLocation().add(0.5, 0.5, 0.5));
                    if (rangeError != null) {
                        return done(Json.error(rangeError));
                    }
                    return plugin.botActions().placeBlock(bot, target, Json.getString(arguments, "item", null));
                });
    }

    private AgentTool findBlock() {
        Schema schema = Schema.object()
                .str("material", "方块类型名，例如 STONE、OAK_LOG、IRON_ORE")
                .integer("radius", "搜索半径，默认 16，最大 32")
                .integer("limit", "最多返回几个结果，默认 5")
                .require("material");
        return Tools.of("find_block", "在附近寻找指定类型的方块，返回最近的几个坐标。找原木时只会返回带树叶的树。", schema, (context, arguments) -> {
            Material material = Material.matchMaterial(
                    Json.getString(arguments, "material", "").trim().toUpperCase(Locale.ROOT));
            if (material == null || material.isAir()) {
                return done(Json.error("无效的方块名，请使用例如 STONE、OAK_LOG、IRON_ORE 这样的名字"));
            }
            Bot bot = context.bot();
            World world = bot.getWorld();
            if (world == null) {
                return done(Json.error("我不在任何世界里"));
            }
            int radius = Math.max(1, Math.min(24, Json.getInt(arguments, "radius", 16)));
            int limit = Math.max(1, Math.min(20, Json.getInt(arguments, "limit", 5)));
            Location origin = bot.getLocation();
            int baseX = origin.getBlockX();
            int baseY = origin.getBlockY();
            int baseZ = origin.getBlockZ();
            int yRadius = Math.min(8, radius);

            List<Block> blocks = new ArrayList<>();
            int maxMatches = TreeGuard.isLog(material) ? Math.max(limit * 8, 200) : limit * 8;
            scan:
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = -yRadius; dy <= yRadius; dy++) {
                        Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                        if (block.getType() != material) {
                            continue;
                        }
                        blocks.add(block);
                        if (blocks.size() >= maxMatches) {
                            break scan;
                        }
                    }
                }
            }
            if (TreeGuard.isLog(material) && plugin.settings().requireLeavesForLogs()) {
                blocks.removeIf(block -> !TreeGuard.isTreeLog(world, block, plugin.settings().logLeafRadius()));
            }
            blocks.sort(Comparator.comparingDouble(block -> block.getLocation().distanceSquared(origin)));

            var results = Json.array();
            for (int index = 0; index < blocks.size() && index < limit; index++) {
                Block block = blocks.get(index);
                results.add(Json.obj(
                        "position", ToolGuards.fmt(block.getLocation()),
                        "distance", Math.round(Math.sqrt(block.getLocation().distanceSquared(origin)) * 10.0) / 10.0,
                        "height_diff", block.getY() - bot.getLocation().getBlockY()));
            }
            if (results.isEmpty()) {
                return done(Json.error("附近 " + radius + " 格内没有找到 " + material.name()));
            }
            return done(Json.ok("找到 " + blocks.size() + " 个 " + material.name(), "blocks", results));
        });
    }

    private AgentTool inspectBlock() {
        Schema schema = Schema.object()
                .number("x", "方块 X 坐标")
                .number("y", "方块 Y 坐标")
                .number("z", "方块 Z 坐标")
                .require("x", "y", "z");
        return Tools.of("inspect_block", "查看某个坐标方块的信息。", schema, (context, arguments) -> {
            Block block = resolveBlock(context.bot(), arguments);
            if (block == null) {
                return done(Json.error("坐标无效"));
            }
            return done(Json.ok(null,
                    "position", ToolGuards.fmt(block.getLocation()),
                    "type", block.getType().name(),
                    "solid", block.getType().isSolid(),
                    "liquid", block.isLiquid(),
                    "passable", block.isPassable(),
                    "powered", block.isBlockPowered()));
        });
    }

    private Block resolveBlock(Bot bot, com.google.gson.JsonObject arguments) {
        World world = bot.getWorld();
        if (world == null) {
            return null;
        }
        double x = Json.getDouble(arguments, "x", Double.NaN);
        double y = Json.getDouble(arguments, "y", Double.NaN);
        double z = Json.getDouble(arguments, "z", Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return null;
        }
        return world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    private CompletableFuture<String> done(String value) {
        return CompletableFuture.completedFuture(value);
    }
}
