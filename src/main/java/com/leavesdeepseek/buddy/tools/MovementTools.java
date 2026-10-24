package com.leavesdeepseek.buddy.tools;

import com.google.gson.JsonObject;
import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.ai.AgentTool;
import com.leavesdeepseek.buddy.ai.Schema;
import com.leavesdeepseek.buddy.ai.Tools;
import com.leavesdeepseek.buddy.bot.BotNavigator;
import com.leavesdeepseek.buddy.util.Json;
import com.leavesdeepseek.buddy.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.entity.bot.action.FishAction;
import org.leavesmc.leaves.entity.bot.action.JumpAction;
import org.leavesmc.leaves.entity.bot.action.SneakAction;
import org.leavesmc.leaves.entity.bot.action.SwimAction;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 移动、视角与状态类动作。
 */
public final class MovementTools {

    private final LeavesDeepSeekPlugin plugin;

    public MovementTools(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public List<AgentTool> all() {
        return List.of(
                moveTo(),
                moveToOwner(),
                followOwner(),
                stopMoving(),
                lookAt(),
                jump(),
                sneak(),
                swim(),
                fish(),
                stopAllActions()
        );
    }

    private AgentTool moveTo() {
        Schema schema = Schema.object()
                .number("x", "目标方块 X 坐标")
                .number("y", "目标方块 Y 坐标（脚的位置）")
                .number("z", "目标方块 Z 坐标")
                .integer("timeout_seconds", "最长行走时间，默认 30 秒")
                .require("x", "y", "z");

        return Tools.of("move_to", "走到指定方块坐标附近。会自动寻路、上下台阶。", schema, (context, arguments) -> {
            Bot bot = context.bot();
            World world = bot.getWorld();
            if (world == null) {
                return done(Json.error("我不在任何世界里"));
            }
            double x = Json.getDouble(arguments, "x", Double.NaN);
            double y = Json.getDouble(arguments, "y", Double.NaN);
            double z = Json.getDouble(arguments, "z", Double.NaN);
            if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
                return done(Json.error("需要提供有效的 x、y、z 坐标"));
            }
            Location target = new Location(world, x, y, z);
            String rangeError = ToolGuards.checkRange(plugin.settings(), context.owner(), target);
            if (rangeError != null) {
                return done(Json.error(rangeError));
            }
            double distance = bot.getLocation().distance(target);
            int timeoutSeconds = Math.max(5, Math.min(120, Json.getInt(arguments, "timeout_seconds", 30)));
            int ticks = Math.min(timeoutSeconds * 20, Math.max(60, (int) (distance * 25)));
            // 最新的移动指令优先：先打断正在进行的挖掘/战斗，再高优先级移动
            plugin.botActions().preempt(bot, "主人让你移动到别处");
            return plugin.navigator().navigateAndWait(bot, target, 1.5, ticks, BotNavigator.PRIORITY_HIGH)
                    .thenApply(arrived -> arrived
                            ? Json.ok("已到达目标附近", "position", ToolGuards.fmt(bot.getLocation()))
                            : Json.error("没能到达目标：路线不存在或被地形挡住了"));
        });
    }

    private AgentTool moveToOwner() {
        Schema schema = Schema.object().integer("timeout_seconds", "最长行走时间，默认 30 秒");
        return Tools.of("move_to_owner", "回到主人身边。", schema, (context, arguments) -> {
            Player owner = context.owner();
            if (owner == null || !owner.isOnline()) {
                return done(Json.error("主人不在线，我不知道该去哪里"));
            }
            Bot bot = context.bot();
            if (!ToolGuards.sameWorld(bot.getLocation(), owner.getLocation())) {
                return done(Json.error("主人在另一个维度，我先待在这里等你回来"));
            }
            int timeoutSeconds = Math.max(5, Math.min(120, Json.getInt(arguments, "timeout_seconds", 30)));
            plugin.botActions().preempt(bot, "主人让你过去");
            return plugin.navigator().navigateAndWait(bot, owner.getLocation(), 2.0, timeoutSeconds * 20,
                            BotNavigator.PRIORITY_HIGH)
                    .thenApply(arrived -> arrived
                            ? Json.ok("已经回到主人身边", "position", ToolGuards.fmt(bot.getLocation()))
                            : Json.error("没能回到主人身边，路上被挡住了"));
        });
    }

    private AgentTool followOwner() {
        Schema schema = Schema.object().number("distance", "保持的距离（格），默认使用服务器配置");
        return Tools.of("follow_owner", "开始持续跟随主人，主人走远时自动跟上。", schema, (context, arguments) -> {
            Player owner = context.owner();
            if (owner == null || !owner.isOnline()) {
                return done(Json.error("主人不在线"));
            }
            double distance = Json.getDouble(arguments, "distance", plugin.settings().followDistance());
            Bot bot = context.bot();
            // 跟随是最高优先级指令：先停掉手上的活，再开始跟
            plugin.botActions().preempt(bot, "主人让你跟上");
            plugin.navigator().forceFollow(bot, owner, Math.max(1.0, Math.min(16.0, distance)));
            return done(Json.ok("开始跟随主人"));
        });
    }

    private AgentTool stopMoving() {
        return Tools.of("stop_moving", "停止当前移动与跳跃，并取消跟随。", Schema.object(), (context, arguments) -> {
            plugin.navigator().stopFollow(context.bot());
            plugin.navigator().cancel(context.bot());
            return done(Json.ok("已经停下"));
        });
    }

    private AgentTool lookAt() {
        Schema schema = Schema.object()
                .number("x", "看向的 X 坐标")
                .number("y", "看向的 Y 坐标")
                .number("z", "看向的 Z 坐标")
                .str("player", "或者看向某个在线玩家名")
                .require();
        return Tools.of("look_at", "转头看向指定坐标或玩家。", schema, (context, arguments) -> {
            Player player = resolvePlayer(arguments);
            if (player != null) {
                plugin.botActions().aimAt(context.bot(), player.getEyeLocation());
                return done(Json.ok("正在看着 " + player.getName()));
            }
            double x = Json.getDouble(arguments, "x", Double.NaN);
            double y = Json.getDouble(arguments, "y", Double.NaN);
            double z = Json.getDouble(arguments, "z", Double.NaN);
            if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
                return done(Json.error("需要提供坐标，或者提供 player 参数"));
            }
            Location point = new Location(context.bot().getWorld(), x, y, z);
            plugin.botActions().aimAt(context.bot(), point);
            return done(Json.ok("已经转头看向目标"));
        });
    }

    private AgentTool jump() {
        return Tools.of("jump", "原地跳一下。", Schema.object(), (context, arguments) -> {
            JumpAction action = JumpAction.create();
            action.setStartDelayTick(0);
            action.setDoIntervalTick(1);
            action.setDoNumber(1);
            context.bot().addAction(action);
            return Tasks.delay(plugin, 5).thenApply(ignored -> Json.ok("跳了一下"));
        });
    }

    private AgentTool sneak() {
        Schema schema = Schema.object().bool("enabled", "true 潜行，false 站起").require("enabled");
        return Tools.of("sneak", "切换潜行状态。", schema, (context, arguments) -> {
            Bot bot = context.bot();
            if (Json.getBoolean(arguments, "enabled", true)) {
                if (!plugin.bots().hasAction(bot, SneakAction.class)) {
                    bot.addAction(SneakAction.create());
                }
                return done(Json.ok("已经开始潜行"));
            }
            plugin.bots().stopActions(bot, SneakAction.class);
            return done(Json.ok("已经站起来了"));
        });
    }

    private AgentTool swim() {
        Schema schema = Schema.object().bool("enabled", "true 开始上浮，false 停止").require("enabled");
        return Tools.of("swim", "在水中自动上浮。", schema, (context, arguments) -> {
            Bot bot = context.bot();
            if (Json.getBoolean(arguments, "enabled", true)) {
                if (!plugin.bots().hasAction(bot, SwimAction.class)) {
                    bot.addAction(SwimAction.create());
                }
                return done(Json.ok("开始上浮"));
            }
            plugin.bots().stopActions(bot, SwimAction.class);
            return done(Json.ok("停止上浮"));
        });
    }

    private AgentTool fish() {
        Schema schema = Schema.object()
                .bool("enabled", "true 开始自动钓鱼，false 停止")
                .integer("interval_ticks", "每次抛竿间隔，默认 20")
                .require("enabled");
        return Tools.of("fish", "自动钓鱼（需要手持钓竿并面向水面）。", schema, (context, arguments) -> {
            Bot bot = context.bot();
            if (!Json.getBoolean(arguments, "enabled", true)) {
                plugin.bots().stopActions(bot, FishAction.class);
                return done(Json.ok("停止钓鱼"));
            }
            if (!plugin.bots().hasAction(bot, FishAction.class)) {
                FishAction action = FishAction.create();
                action.setStartDelayTick(5);
                action.setDoIntervalTick(Math.max(5, Json.getInt(arguments, "interval_ticks", 20)));
                action.setDoNumber(-1);
                bot.addAction(action);
            }
            return done(Json.ok("开始自动钓鱼"));
        });
    }

    private AgentTool stopAllActions() {
        return Tools.of("stop_all_actions", "停止所有正在进行的动作（移动、挖掘、攻击、钓鱼、跟随等）。", Schema.object(),
                (context, arguments) -> {
                    plugin.navigator().stopFollow(context.bot());
                    plugin.botActions().preempt(context.bot(), "主人让你停下手上的事");
                    return done(Json.ok("已停止全部动作"));
                });
    }

    private Player resolvePlayer(JsonObject arguments) {
        String name = Json.getString(arguments, "player", null);
        if (name == null || name.isBlank()) {
            return null;
        }
        return Bukkit.getPlayerExact(name);
    }

    private CompletableFuture<String> done(String value) {
        return CompletableFuture.completedFuture(value);
    }
}
