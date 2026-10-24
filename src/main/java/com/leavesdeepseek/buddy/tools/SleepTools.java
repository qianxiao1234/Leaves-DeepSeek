package com.leavesdeepseek.buddy.tools;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.ai.AgentTool;
import com.leavesdeepseek.buddy.ai.Schema;
import com.leavesdeepseek.buddy.ai.Tools;
import com.leavesdeepseek.buddy.util.Json;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 找床、睡觉、起床。
 */
public final class SleepTools {

    private final LeavesDeepSeekPlugin plugin;

    public SleepTools(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public List<AgentTool> all() {
        return List.of(findBed(), sleepInBed(), wakeUp());
    }

    private AgentTool findBed() {
        Schema schema = Schema.object()
                .integer("radius", "搜索半径，默认 12，最大 24");
        return Tools.of("find_bed", "在附近寻找床，返回床的位置和是否被占用。", schema, (context, arguments) -> {
            Bot bot = context.bot();
            World world = bot.getWorld();
            if (world == null) {
                return done(Json.error("我不在任何世界里"));
            }
            int radius = Math.max(1, Math.min(24, Json.getInt(arguments, "radius", 12)));
            Location origin = bot.getLocation();
            int baseX = origin.getBlockX();
            int baseY = origin.getBlockY();
            int baseZ = origin.getBlockZ();

            List<Block> beds = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                        if (block.getBlockData() instanceof Bed bed && bed.getPart() == Bed.Part.HEAD) {
                            beds.add(block);
                        }
                    }
                }
            }
            beds.sort(Comparator.comparingDouble(block -> block.getLocation().distanceSquared(origin)));
            if (beds.isEmpty()) {
                return done(Json.error("附近 " + radius + " 格内没有找到床"));
            }

            var results = Json.array();
            for (int index = 0; index < beds.size() && index < 8; index++) {
                Block bed = beds.get(index);
                Bed data = (Bed) bed.getBlockData();
                results.add(Json.obj(
                        "position", ToolGuards.fmt(bed.getLocation()),
                        "distance", Math.round(Math.sqrt(bed.getLocation().distanceSquared(origin)) * 10.0) / 10.0,
                        "occupied", data.isOccupied()));
            }
            boolean night = !world.isDayTime() || world.isThundering();
            return done(Json.ok("找到 " + beds.size() + " 张床" + (night ? "" : "（现在是白天，睡觉可能失败）"),
                    "beds", results));
        });
    }

    private AgentTool sleepInBed() {
        Schema schema = Schema.object()
                .number("x", "床的 X 坐标，可省略（省略时自动找附近的床）")
                .number("y", "床的 Y 坐标")
                .number("z", "床的 Z 坐标")
                .integer("radius", "自动找床的半径，默认 16");
        return Tools.of("sleep_in_bed", "走到床边并尝试睡觉。不填坐标时会自动寻找最近的床。", schema,
                (context, arguments) -> {
                    Bot bot = context.bot();
                    World world = bot.getWorld();
                    if (world == null) {
                        return done(Json.error("我不在任何世界里"));
                    }
                    Block bed;
                    double x = Json.getDouble(arguments, "x", Double.NaN);
                    double y = Json.getDouble(arguments, "y", Double.NaN);
                    double z = Json.getDouble(arguments, "z", Double.NaN);
                    if (!Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z)) {
                        bed = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                    } else {
                        int radius = Math.max(2, Math.min(32, Json.getInt(arguments, "radius", 16)));
                        bed = plugin.botActions().findNearestBed(bot, radius);
                    }
                    if (bed == null) {
                        return done(Json.error("附近没有找到床，请先放一张床"));
                    }
                    return plugin.botActions().sleepInBed(bot, bed);
                });
    }

    private AgentTool wakeUp() {
        return Tools.of("wake_up", "从床上起来。", Schema.object(), (context, arguments) ->
                done(plugin.botActions().wakeUp(context.bot())
                        ? Json.ok("已经起床了")
                        : Json.error("我现在没有在睡觉")));
    }

    private CompletableFuture<String> done(String value) {
        return CompletableFuture.completedFuture(value);
    }
}
