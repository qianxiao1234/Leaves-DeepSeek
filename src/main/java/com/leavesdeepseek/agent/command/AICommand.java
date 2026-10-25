package com.leavesdeepseek.agent.command;

import com.leavesdeepseek.agent.AIBody;
import com.leavesdeepseek.agent.BodyManager;
import com.leavesdeepseek.agent.JsonRpc;
import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /ai} 指令树（规格 §4）。
 *
 * <p>只管躯壳本体，不碰传统假人的 {@code /buddy}。</p>
 */
public final class AICommand implements CommandExecutor, TabCompleter {

    private static final List<String> BODY_SUBS = List.of(
            "create", "list", "info", "remove", "save", "load", "take", "release",
            "move", "anchor", "config", "reload");

    private final LeavesDeepSeekPlugin plugin;

    public AICommand(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    private BodyManager manager() {
        return plugin.bodyManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        if (!args[0].equalsIgnoreCase("body")) {
            if (args[0].equalsIgnoreCase("help")) {
                help(sender, label);
                return true;
            }
            Text.send(sender, "§c未知子命令：§f" + args[0] + "§7（用 §f/" + label + " help§7）");
            return true;
        }
        if (manager() == null) {
            Text.send(sender, "§c躯壳子系统没有初始化（检查 config.yml 的 agent:/body: 段）。");
            return true;
        }
        if (args.length < 2) {
            help(sender, label);
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("leavesdeepseek.ai.admin")
                && !sender.hasPermission("leavesdeepseek.ai.body." + sub)) {
            Text.send(sender, "§c你没有权限：§fleavesdeepseek.ai.body." + sub);
            return true;
        }
        try {
            switch (sub) {
                case "create" -> create(sender, args);
                case "list" -> list(sender);
                case "info" -> info(sender, args);
                case "remove" -> remove(sender, args);
                case "save" -> save(sender, args);
                case "load" -> load(sender, args);
                case "take" -> take(sender, args);
                case "release" -> release(sender, args);
                case "move" -> move(sender, args);
                case "anchor" -> anchor(sender, args);
                case "config" -> config(sender, args);
                case "reload" -> reload(sender);
                default -> help(sender, label);
            }
        } catch (JsonRpc.RpcException exception) {
            Text.send(sender, "§c" + exception.getMessage());
        } catch (Throwable throwable) {
            Text.send(sender, "§c操作失败：" + throwable.getMessage());
            plugin.log().error("躯壳", "命令执行失败：" + throwable.getMessage(), throwable);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        Text.send(sender, "§8§m----------§r §bAI 躯壳 §8§m----------");
        Text.send(sender, "§f/" + label + " body create <name> [world] [x y z] §7- 造一具躯壳");
        Text.send(sender, "§f/" + label + " body list §7- 列出所有躯壳");
        Text.send(sender, "§f/" + label + " body info <name> §7- 查看状态 / 坐标 / 血量");
        Text.send(sender, "§f/" + label + " body remove <name> [--save] §7- 移除躯壳");
        Text.send(sender, "§f/" + label + " body save|load <name> §7- 存档 / 读档");
        Text.send(sender, "§f/" + label + " body take <name> §7- 踢掉当前控制会话");
        Text.send(sender, "§f/" + label + " body release <name> §7- 释放控制（等 AI 重连）");
        Text.send(sender, "§f/" + label + " body move <name> <x y z> [world] §7- 卡地形救场");
        Text.send(sender, "§f/" + label + " body anchor <name> set|clear §7- 死亡重生点");
        Text.send(sender, "§f/" + label + " body config <name> <key> <value> §7- 单躯壳覆盖配置");
        Text.send(sender, "§f/" + label + " body reload §7- 重新读 agent:/body: 配置");
        Text.send(sender, "§7外部 AI 通过 WebSocket（§fws://" + configHint() + "§7）接管躯壳。");
    }

    private String configHint() {
        try {
            return manager().config().bind() + ":" + manager().config().port() + "/agent";
        } catch (Throwable throwable) {
            return "127.0.0.1:8123/agent";
        }
    }

    private void create(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Text.send(sender, "§7用法：§f/ai body create <name> [world] [x y z]");
            return;
        }
        String name = args[2];
        World world = null;
        Location location = null;
        if (args.length >= 4) {
            world = Bukkit.getWorld(args[3]);
            if (world == null) {
                Text.send(sender, "§c世界不存在：" + args[3]);
                return;
            }
        }
        if (args.length >= 7 && world != null) {
            try {
                location = new Location(world, Double.parseDouble(args[4]),
                        Double.parseDouble(args[5]), Double.parseDouble(args[6]));
            } catch (NumberFormatException exception) {
                Text.send(sender, "§c坐标必须是数字");
                return;
            }
        }
        if (location == null) {
            if (world != null) {
                location = world.getSpawnLocation();
            } else if (sender instanceof Player player) {
                location = player.getLocation();
            }
        }
        AIBody body = manager().create(name, location);
        Location where = body.location();
        Text.send(sender, "§a躯壳已创建：§f" + body.name() + " §7@ "
                + (where == null ? "?" : where.getWorld().getName() + " "
                + round(where.getX()) + " " + round(where.getY()) + " " + round(where.getZ())));
        Text.send(sender, "§7状态 paused（等外部 AI attach）：§f" + configHint());
    }

    private void list(CommandSender sender) {
        List<AIBody> bodies = new ArrayList<>(manager().bodies());
        if (bodies.isEmpty()) {
            Text.send(sender, "§7还没有任何躯壳，用 §f/ai body create <name> §7造一个。");
            return;
        }
        Text.send(sender, "§8§m----------§r §b躯壳列表 §8§m----------");
        for (AIBody body : bodies) {
            Text.send(sender, "§f" + manager().describe(body)
                    + (body.session() == null ? " §7(未连接)" : " §a(已连接)"));
        }
    }

    private void info(CommandSender sender, String[] args) {
        AIBody body = manager().require(arg(args, 2));
        Text.send(sender, "§8§m----------§r §b" + body.name() + " §8§m----------");
        Text.send(sender, "§7状态：§f" + body.state().name().toLowerCase(Locale.ROOT)
                + (body.session() == null ? " §7未连接" : " §a已连接"));
        Location location = body.location();
        if (location != null) {
            Text.send(sender, "§7位置：§f" + location.getWorld().getName() + " "
                    + round(location.getX()) + " " + round(location.getY()) + " " + round(location.getZ()));
        }
        if (body.anchor() != null) {
            Location anchor = body.anchor();
            Text.send(sender, "§7锚点：§f" + round(anchor.getX()) + " " + round(anchor.getY())
                    + " " + round(anchor.getZ()));
        }
        Text.send(sender, "§7任务：§f" + (body.controller().current() == null ? "空闲"
                : body.controller().current().type() + " (" + body.controller().current().id() + ")")
                + " §7队列：§f" + Math.max(0, body.controller().snapshot().size() - 1));
        if (!body.overrides().isEmpty()) {
            Text.send(sender, "§7覆盖配置：§f" + body.overrides());
        }
    }

    private void remove(CommandSender sender, String[] args) {
        String name = arg(args, 2);
        boolean save = true;
        for (String value : args) {
            if (value.equalsIgnoreCase("--save")) {
                save = true;
            }
            if (value.equalsIgnoreCase("--nosave") || value.equalsIgnoreCase("--discard")) {
                save = false;
            }
        }
        if (!manager().remove(name, save)) {
            Text.send(sender, "§c没有这个躯壳：" + name);
            return;
        }
        Text.send(sender, "§a已移除躯壳 §f" + name + (save ? "§a（存档保留）" : "§a（存档已删除）"));
    }

    private void save(CommandSender sender, String[] args) {
        String name = arg(args, 2);
        if (!manager().save(name)) {
            Text.send(sender, "§c保存失败（躯壳不在线？）");
            return;
        }
        Text.send(sender, "§a已保存 §f" + name);
    }

    private void load(CommandSender sender, String[] args) {
        AIBody body = manager().require(arg(args, 2));
        manager().bringOnline(body, "load");
        Location location = body.location();
        Text.send(sender, "§a已读档并上线：§f" + body.name()
                + (location == null ? "" : " §7@ " + round(location.getX()) + " "
                + round(location.getY()) + " " + round(location.getZ())));
    }

    private void take(CommandSender sender, String[] args) {
        AIBody body = manager().require(arg(args, 2));
        if (body.session() == null) {
            Text.send(sender, "§7这个躯壳当前没有连接。");
            return;
        }
        if (manager().server() != null) {
            manager().server().kick(body);
        } else {
            body.session(null);
        }
        Text.send(sender, "§a已踢掉 §f" + body.name() + " §a的控制会话。");
    }

    private void release(CommandSender sender, String[] args) {
        AIBody body = manager().require(arg(args, 2));
        if (body.session() != null) {
            body.session().bodies().remove(body.name());
        }
        body.session(null);
        body.touch();
        Text.send(sender, "§a已释放 §f" + body.name() + "§a，"
                + manager().config().idleToPausedSeconds() + " 秒后自动进旁观。");
    }

    private void move(CommandSender sender, String[] args) {
        AIBody body = manager().require(arg(args, 2));
        if (args.length < 6) {
            Text.send(sender, "§7用法：§f/ai body move <name> <x> <y> <z> [world]");
            return;
        }
        World world = args.length >= 7 ? Bukkit.getWorld(args[6]) : body.world();
        if (world == null) {
            Text.send(sender, "§c世界不存在");
            return;
        }
        try {
            Location target = new Location(world, Double.parseDouble(args[3]),
                    Double.parseDouble(args[4]), Double.parseDouble(args[5]));
            if (body.bot() == null) {
                Text.send(sender, "§c躯壳不在线");
                return;
            }
            body.bot().teleport(target);
            Text.send(sender, "§a已移动 §f" + body.name() + " §a到 "
                    + round(target.getX()) + " " + round(target.getY()) + " " + round(target.getZ()));
        } catch (NumberFormatException exception) {
            Text.send(sender, "§c坐标必须是数字");
        }
    }

    private void anchor(CommandSender sender, String[] args) {
        AIBody body = manager().require(arg(args, 2));
        String action = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "set";
        if (action.equals("clear")) {
            body.anchor(null);
            manager().save(body.name());
            Text.send(sender, "§a已清除 §f" + body.name() + " §a的锚点。");
            return;
        }
        Location location = body.location();
        if (location == null) {
            Text.send(sender, "§c躯壳不在世界内，无法设置锚点");
            return;
        }
        body.anchor(location.clone());
        manager().save(body.name());
        Text.send(sender, "§a已把 §f" + body.name() + " §a的锚点设为当前位置："
                + round(location.getX()) + " " + round(location.getY()) + " " + round(location.getZ()));
    }

    private void config(CommandSender sender, String[] args) {
        AIBody body = manager().require(arg(args, 2));
        if (args.length < 5) {
            Text.send(sender, "§7用法：§f/ai body config <name> <key> <value>");
            Text.send(sender, "§7可用 key：§fallow-pvp§7, §fallow-container§7, §fallow-drop-item§7, "
                    + "§fauto-eat-threshold§7（value 写 §fclear§7 表示恢复默认）");
            return;
        }
        String key = args[3].toLowerCase(Locale.ROOT);
        String value = args[4];
        if (value.equalsIgnoreCase("clear")) {
            body.override(key, null);
            Text.send(sender, "§a已清除 §f" + body.name() + "§a 的覆盖项 §f" + key);
            return;
        }
        body.override(key, value);
        Text.send(sender, "§a已设置 §f" + body.name() + "§a 的 §f" + key + "§a = §f" + value);
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        manager().config().reload();
        Text.send(sender, "§a已重新读取 agent:/body: 配置。"
                + "§7（端口与 token 变更需要重启服务器才生效）");
    }

    private static String arg(String[] args, int index) {
        if (args.length <= index) {
            throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST, "缺少躯壳名字");
        }
        return args[index];
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value * 10) / 10.0);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("body", "help"), args[0]);
        }
        if (!args[0].equalsIgnoreCase("body")) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(BODY_SUBS, args[1]);
        }
        if (args.length == 3) {
            List<String> names = manager() == null ? List.of() : manager().names();
            return filter(names, args[2]);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("anchor")) {
            return filter(List.of("set", "clear"), args[3]);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("config")) {
            return filter(List.of("allow-pvp", "allow-container", "allow-drop-item",
                    "auto-eat-threshold"), args[3]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String typed) {
        String lower = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
