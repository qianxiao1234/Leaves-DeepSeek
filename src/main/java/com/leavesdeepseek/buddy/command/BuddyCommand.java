package com.leavesdeepseek.buddy.command;

import com.google.gson.JsonObject;
import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.ai.AiBackend;
import com.leavesdeepseek.buddy.ai.AiDefaults;
import com.leavesdeepseek.buddy.ai.ApiConfig;
import com.leavesdeepseek.buddy.ai.AstrBotConfig;
import com.leavesdeepseek.buddy.ai.ResolvedAi;
import com.leavesdeepseek.buddy.bot.BotNames;
import com.leavesdeepseek.buddy.bot.BotSnapshot;
import com.leavesdeepseek.buddy.config.PlayerProfile;
import com.leavesdeepseek.buddy.config.PlayerProfiles;
import com.leavesdeepseek.buddy.util.Json;
import com.leavesdeepseek.buddy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /buddy 命令。
 */
public final class BuddyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of(
            "help", "status", "bot", "move", "do", "ai", "astrbot", "global", "reload", "debug");

    private final LeavesDeepSeekPlugin plugin;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public BuddyCommand(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(sender, label);
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            case "debug" -> debug(sender, args);
            case "bot" -> botGroup(sender, args, label);
            case "move" -> moveGroup(sender, args, label);
            case "do" -> doGroup(sender, args, label);
            case "ai" -> aiGroup(sender, args, label);
            // astrbot / global 的分类内部自己带子命令，保持原样
            case "astrbot" -> astrBot(sender, args);
            case "global" -> global(sender, args);
            default -> {
                Text.send(sender, "§c未知子命令：" + args[0] + "§7（用 §f/" + label + " help§7 看全部）");
            }
        }
        return true;
    }

    /** 去掉分组名，保留「组内子命令 + 参数」。 */
    private static String[] inner(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private void botGroup(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            Text.send(sender, "§7用法：§f/" + label + " bot <spawn|remove|list|revive|info>");
            return;
        }
        String[] sub = inner(args);
        switch (sub[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> spawn(sender, sub);
            case "remove" -> remove(sender, sub);
            case "list" -> list(sender);
            case "revive" -> revive(sender);
            case "info", "status" -> status(sender);
            default -> Text.send(sender, "§c用法：§f/" + label + " bot <spawn|remove|list|revive|info>");
        }
    }

    private void moveGroup(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            Text.send(sender, "§7用法：§f/" + label + " move <follow|come|stay|stop|sleep>");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "follow" -> follow(sender);
            case "come" -> come(sender);
            case "stay" -> stay(sender);
            case "stop" -> stop(sender);
            case "sleep" -> sleep(sender);
            default -> Text.send(sender, "§c用法：§f/" + label + " move <follow|come|stay|stop|sleep>");
        }
    }

    private void doGroup(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            Text.send(sender, "§7用法：§f/" + label + " do <eat|say|task|listen>");
            return;
        }
        String[] sub = inner(args);
        switch (sub[0].toLowerCase(Locale.ROOT)) {
            case "eat" -> eat(sender, sub);
            case "say" -> say(sender, sub);
            case "task", "chat" -> task(sender, sub);
            case "listen" -> listen(sender, sub);
            default -> Text.send(sender, "§c用法：§f/" + label + " do <eat|say|task|listen>");
        }
    }

    private void aiGroup(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            Player player = asPlayer(sender);
            if (player != null) {
                aiStatus(player);
            }
            return;
        }
        String[] sub = inner(args);
        switch (sub[0].toLowerCase(Locale.ROOT)) {
            case "set" -> set(sender, sub);
            case "clean" -> clean(sender);
            case "backend", "use" -> aiBackend(sender, sub);
            case "status", "info" -> {
                Player player = asPlayer(sender);
                if (player != null) {
                    aiStatus(player);
                }
            }
            case "persona" -> persona(sender, sub);
            default -> Text.send(sender, "§c用法：§f/" + label
                    + " ai <set|clean|backend|status|persona>");
        }
    }

    private void help(CommandSender sender, String label) {
        Text.send(sender, "§8§m----------§r §bLeavesDeepSeek §8§m----------");
        Text.send(sender, "§b【假人】");
        Text.send(sender, "§f/" + label + " bot spawn [名字] [皮肤名] §7- 创建你的 AI 伙伴假人");
        Text.send(sender, "§f/" + label + " bot remove [名字] §7- 移除伙伴");
        Text.send(sender, "§f/" + label + " bot list §7- 查看自己的伙伴");
        Text.send(sender, "§f/" + label + " bot revive §7- 把倒下的伙伴救起来（物品一件不丢）");
        Text.send(sender, "§f/" + label + " bot info §7- 查看伙伴状态");
        Text.send(sender, "§b【移动】");
        Text.send(sender, "§f/" + label + " move follow §7- 持续跟随你");
        Text.send(sender, "§f/" + label + " move come §7- 过来一下");
        Text.send(sender, "§f/" + label + " move stay §7- 原地待命（关闭跟随）");
        Text.send(sender, "§f/" + label + " move stop §7- 停止全部动作并关闭跟随");
        Text.send(sender, "§f/" + label + " move sleep §7- 找附近的床睡觉");
        Text.send(sender, "§b【做事】");
        Text.send(sender, "§f/" + label + " do task <要做的事> §7- 用自然语言指挥伙伴（核心功能）");
        Text.send(sender, "§f/" + label + " do say <内容> §7- 让伙伴说话");
        Text.send(sender, "§f/" + label + " do eat [食物] §7- 让伙伴吃东西（主人命令视为同意）");
        Text.send(sender, "§f/" + label + " do listen [on|off] §7- 切换是否直接接管你的聊天");
        Text.send(sender, "§b【AI】");
        Text.send(sender, "§f/" + label + " ai set <apikey> [model] [base-url] §7- 配置并验证你自己的 API Key");
        Text.send(sender, "§f/" + label + " ai clean §7- 清空你自己的 Key/模型/地址（恢复默认值）");
        Text.send(sender, "§f/" + label + " ai backend <auto|deepseek|astrbot> §7- 选择默认使用的 AI 后端");
        Text.send(sender, "§f/" + label + " ai status §7- 查看 AI 配置与最近错误");
        Text.send(sender, "§f/" + label + " ai persona [set <描述>|show|reset] §7- 设置人格与说话风格");
        Text.send(sender, "§f/" + label + " astrbot <set|on|off|test|status|session|clear|say> §7- 对接你自己的 AstrBot");
        Text.send(sender, "§b【管理员】");
        Text.send(sender, "§f/" + label + " global <set|clean|ai|astrbot|reload|debug> §7- 修改服务器全局配置");
        Text.send(sender, "§f/" + label + " reload §7- 重载配置");
        Text.send(sender, "§f/" + label + " debug <on|off> §7- 调试日志开关");
        Text.send(sender, "§f/" + label + " status §7- 查看伙伴状态");
        Text.send(sender, "§7也可以直接使用聊天前缀，例如：§f@ 帮我砍三根木头");
    }

    private void spawn(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (!player.hasPermission("leavesdeepseek.spawn")) {
            Text.send(player, "§c你没有权限创建伙伴。");
            return;
        }
        if (plugin.bots().countOf(player) >= plugin.settings().maxPerPlayer()) {
            Text.send(player, "§e你已经有 " + plugin.settings().maxPerPlayer()
                    + " 个伙伴了，可以先用 §f/buddy bot remove §e移除一个。");
            return;
        }

        ResolvedAi resolved = plugin.ai().resolve(player);
        boolean aiReady = resolved.usable();
        if (!aiReady) {
            // 没有 AI 也能创建假人：跟随/过来/待命/睡觉/吃东西/看状态这些命令都不需要大模型
            if (resolved.needsChoice()) {
                Text.send(player, "§e你同时配置了 DeepSeek 和 AstrBot，聊天前需要先选一个默认后端：");
                Text.send(player, "§f/buddy ai backend deepseek §7- 使用 DeepSeek（可操作假人）");
                Text.send(player, "§f/buddy ai backend astrbot §7- 使用 AstrBot（纯聊天）");
            } else {
                Text.send(player, "§e注意：你还没有可用的 AI，假人照样能创建，只是暂时只能用命令控制：");
                Text.send(player, "§7  §f/buddy move follow §7跟随 | §f/buddy move come §7过来 | §f/buddy move stay §7待命");
                Text.send(player, "§7  §f/buddy move sleep §7睡觉 | §f/buddy do eat §7吃东西 | §f/buddy status §7看状态");
                Text.send(player, "§7想让它听懂自然语言、自己动手：§f/buddy ai set <apikey> [model] [base-url]");
                Text.send(player, "§7（或者让服主用 §f/buddy global set <apikey>§7 配一个全局 Key）");
            }
        } else if (!resolved.astrBot() && !resolved.validated()) {
            // 后台验证一次，只做提示，不阻塞创建
            Text.send(player, "§7正在后台验证 " + resolved.keySource().label() + "……");
            plugin.ai().resolveValidated(player).whenComplete((validated, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null || !validated.deepSeek()) {
                            String reason = error != null ? Json.rootMessage(error) : validated.reason();
                            Text.send(player, "§cAI 验证失败：" + reason);
                            Text.send(player, "§7假人已经创建，仍可用 §f/buddy§7 命令操作；修好 Key 后聊天功能会自动生效。");
                            return;
                        }
                        Text.send(player, "§aAI 配置验证通过（" + validated.keySource().label()
                                + "），可以直接聊天指挥了。");
                    }));
        }
        doSpawn(player, args, aiReady);
    }

    private void doSpawn(Player player, String[] args, boolean aiReady) {
        String name = args.length > 1 ? args[1] : null;
        String skin = args.length > 2 ? args[2] : null;
        Text.send(player, "§7正在创建伙伴假人……");

        plugin.bots().create(player, name, skin).whenComplete((bot, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        Text.send(player, "§c创建失败：" + Json.rootMessage(error));
                        return;
                    }
                    if (bot == null) {
                        Text.send(player, "§c创建失败，请查看服务器日志（确认假人功能已开启）。");
                        return;
                    }
                    Text.send(player, "§a伙伴 §f" + BotNames.rawName(bot) + " §a已上线！");
                    if (aiReady) {
                        Text.send(player, "§7直接聊天就能指挥我；想暂时关掉直听用 §f/buddy do listen off§7。");
                    } else {
                        Text.send(player, "§7目前还不能听懂聊天（没有 AI），请用 §f/buddy move follow §7/"
                                + " §f/buddy move come §7/ §f/buddy move stay §7等命令。");
                    }
                    if (plugin.profiles().autoFollow(player)) {
                        plugin.navigator().follow(bot, player, plugin.settings().followDistance());
                    }
                }));
    }

    private void set(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            Text.send(player, "§7用法：§f/buddy ai set <apikey> [model] [base-url]");
            Text.send(player, "§7不填 model / base-url，或填 §fdefault§7，表示使用服务器默认值。");
            Text.send(player, "§7如果要填 base-url，必须同时填 model。");
            return;
        }

        String key = args[1].trim();
        if (key.isEmpty() || key.equalsIgnoreCase("default")) {
            Text.send(player, "§cAPI Key 不能为空。");
            return;
        }
        String model = args.length > 2 ? args[2].trim() : "default";
        String baseUrl = args.length > 3 ? args[3].trim() : "default";

        if (args.length == 3 && looksLikeUrl(model)) {
            Text.send(player, "§c如果填写 base-url，必须同时填写 model：");
            Text.send(player, "§f/buddy ai set <apikey> <model> <base-url>");
            return;
        }
        if (!PlayerProfiles.isDefault(baseUrl) && PlayerProfiles.isDefault(model)) {
            Text.send(player, "§c填写 base-url 时必须同时填写 model。");
            return;
        }
        if (!PlayerProfiles.isDefault(baseUrl) && !looksLikeUrl(baseUrl)) {
            Text.send(player, "§cbase-url 必须以 http:// 或 https:// 开头。");
            return;
        }

        PlayerProfile profile = plugin.profiles().getOrCreate(player);
        profile.setApiKey(key);
        profile.setModel(PlayerProfiles.isDefault(model) ? "default" : model);
        profile.setBaseUrl(PlayerProfiles.isDefault(baseUrl) ? "default" : PlayerProfiles.normalizeBaseUrl(baseUrl));
        profile.setValidated(false);
        plugin.profiles().save(profile);

        ApiConfig api = plugin.ai().ownApi(profile);
        if (api == null) {
            Text.send(player, "§c配置不完整，请检查 api key / model / base-url。");
            return;
        }

        Text.send(player, "§7正在验证 API Key（模型 " + api.model() + "，地址 "
                + plugin.client().endpointOf(api) + "）……");
        plugin.client().validate(api).whenComplete((ignored, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.profiles().markValidated(profile, false);
                        Text.send(player, "§c验证失败：" + Json.rootMessage(error));
                        Text.send(player, "§7Key 已保存但标记为无效，修正后重新执行 /buddy ai set。");
                        return;
                    }
                    plugin.profiles().markValidated(profile, true);
                    Text.send(player, "§aAPI Key 验证通过！模型：§f" + api.model()
                            + " §7地址：§f" + api.baseUrl());
                    Text.send(player, "§7现在可以使用 §f/buddy bot spawn §7创建你的伙伴。");
                    if (plugin.ai().astrBotAvailable(player)) {
                        Text.send(player, "§e你现在同时可用 DeepSeek 和 AstrBot："
                                + "用 §f/buddy ai backend deepseek|astrbot §e选择默认模型。");
                    }
                }));
    }

    private boolean looksLikeUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    /**
     * /buddy ai clean：清空玩家自己的 DeepSeek Key / 模型 / 地址，并恢复默认值。
     * <p>默认值：Key 为空、模型 {@code deepseek-chat}、地址 {@code https://api.deepseek.com}。
     * 清理后玩家的下一次请求会使用服务器全局 Key（如果服主配置了）。</p>
     */
    private void clean(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        PlayerProfile profile = plugin.profiles().getOrCreate(player);
        boolean hadKey = profile.getApiKey() != null && !profile.getApiKey().isBlank();
        boolean hadCustom = !PlayerProfiles.isDefault(profile.getModel())
                || !PlayerProfiles.isDefault(profile.getBaseUrl());
        profile.setApiKey(AiDefaults.API_KEY);
        profile.setModel(AiDefaults.MODEL);
        profile.setBaseUrl(AiDefaults.BASE_URL);
        profile.setValidated(false);
        profile.setValidatedAt(0L);
        plugin.profiles().save(profile);
        plugin.log().info("配置", player.getName() + " 清空了个人的 DeepSeek 配置（恢复默认值）");
        Text.send(player, "§a已清空你自己的 DeepSeek 配置：");
        Text.send(player, "§7  API Key：§f空 §7| 模型：§f" + AiDefaults.MODEL
                + " §7| 地址：§f" + AiDefaults.BASE_URL);
        if (hadKey || hadCustom) {
            Text.send(player, "§7旧的 Key / 模型 / 地址已删除，不会再被使用。");
        }
        if (plugin.settings().useGlobalConfig() && plugin.ai().globalApi() != null) {
            Text.send(player, "§7没有个人 Key 时会自动使用服主配置的全局 Key。");
        } else {
            Text.send(player, "§7需要用时重新执行 §f/buddy ai set <apikey> [model] [base-url]§7。");
        }
    }

    /**
     * /buddy bot revive：把倒下的伙伴救起来；假人不在线时尝试从存档重新生成。
     */
    private void revive(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Bot bot = plugin.bots().primaryBot(player);
        if (bot == null) {
            if (plugin.botStates() != null && plugin.botStates().hasState(player.getUniqueId())) {
                Text.send(player, "§7正在从存档唤醒你的伙伴……");
                if (!plugin.revivePrimary(player)) {
                    Text.send(player, "§c没能从存档唤醒伙伴，请检查数据目录或重新创建。");
                }
            } else {
                Text.send(player, "§e你还没有伙伴，用 §f/buddy bot spawn§e 创建一个。");
            }
            return;
        }
        if (!plugin.bots().isDowned(bot) && !bot.isDead()) {
            Text.send(player, "§7" + BotNames.rawName(bot) + " 好好的，不需要复活。");
            return;
        }
        if (!plugin.reviveBot(bot, player)) {
            Text.send(player, "§c没能复活伙伴，请稍后再试。");
            return;
        }
        Text.send(player, "§a" + BotNames.rawName(bot) + " 已经站起来了（物品一件没少）。");
        if (plugin.settings().speakInChat() && bot.isValid()) {
            bot.chat("我又站起来了！");
        }
    }

    private void remove(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Bot bot;
        if (args.length > 1) {
            bot = plugin.bots().byName(args[1]);
            if (bot == null) {
                Text.send(player, "§c找不到假人 " + args[1]);
                return;
            }
            UUID ownerId = plugin.bots().ownerOf(bot);
            if (!player.hasPermission("leavesdeepseek.admin")
                    && (ownerId == null || !ownerId.equals(player.getUniqueId()))) {
                Text.send(player, "§c这不是你的伙伴。");
                return;
            }
        } else {
            bot = plugin.bots().primaryBot(player);
            if (bot == null) {
                Text.send(player, "§e你还没有伙伴，先用 §f/buddy bot spawn §e创建一个吧。");
                return;
            }
        }
        plugin.navigator().cancel(bot);
        // 必须在 remove 之前取主人 UUID（remove 内部会解绑）
        UUID ownerId = plugin.bots().ownerOf(bot);
        String botName = BotNames.rawName(bot);
        boolean removed = plugin.bots().remove(bot, plugin.settings().saveOnRemove());
        if (removed && ownerId != null && plugin.botStates() != null) {
            // 只有明确执行 /buddy bot remove 才删除存档；服务端停机时的移除不会删
            plugin.botStates().delete(ownerId);
            plugin.getLogger().info("[伙伴] 已删除假人存档：" + botName + "（主人 " + ownerId + "）");
        }
        Text.send(player, removed ? "§a已移除伙伴 §f" + botName : "§c移除失败，请查看日志。");
    }

    private void list(CommandSender sender) {
        Player player = sender instanceof Player p ? p : null;
        if (player == null) {
            Text.send(sender, "§7在线假人：" + plugin.bots().allBots().size());
            return;
        }
        List<Bot> bots = plugin.bots().botsOf(player);
        if (bots.isEmpty()) {
            Text.send(player, "§e你还没有伙伴，使用 §f/buddy bot spawn §e创建一个。");
            return;
        }
        Text.send(player, "§7你的伙伴：");
        for (Bot bot : bots) {
            Text.send(player, "§8- §f" + BotNames.rawName(bot) + (bot.isValid() ? " §a在线" : " §c离线"));
        }
    }

    private void come(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Bot bot = requireBot(player);
        if (bot == null || blockedByDowned(player, bot)) {
            return;
        }
        Text.send(player, "§7我正在过来……");
        plugin.navigator().navigateAndWait(bot, player.getLocation(), 2.0, 400)
                .whenComplete((arrived, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.log().error("命令", "/buddy move come 导航异常", error);
                        Text.send(player, "§c过来的时候出错了：" + Json.rootMessage(error));
                        return;
                    }
                    Text.send(player, arrived ? "§a我到了！" : "§c路上被挡住了，过不去……");
                }));
    }

    private void follow(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Bot bot = requireBot(player);
        if (bot == null || blockedByDowned(player, bot)) {
            return;
        }
        boolean started = plugin.followBot(bot, player, "主人用命令让你跟上");
        String reason = started ? null : plugin.followDeferReason(bot);
        Text.send(player, started
                ? "§a我会跟着你的。"
                : ("creeper".equals(reason)
                        ? "§e有苦力怕，我先跑开，安全后马上跟上。"
                        : "§e我先把这口吃的咽下去，马上跟上。"));
    }

    private void stay(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Bot bot = requireBot(player);
        if (bot == null) {
            return;
        }
        plugin.navigator().stopFollow(bot);
        plugin.navigator().cancel(bot);
        Text.send(player, "§7我就在这里等你。");
    }

    private void sleep(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Bot bot = requireBot(player);
        if (bot == null || blockedByDowned(player, bot)) {
            return;
        }
        org.bukkit.block.Block bed = plugin.botActions().findNearestBed(bot, 16);
        if (bed == null) {
            Text.send(player, "§e附近 16 格内没有找到床。");
            return;
        }
        Text.send(player, "§7我去找床睡觉……");
        plugin.botActions().sleepInBed(bot, bed).whenComplete((result, error) -> {
            if (error != null) {
                plugin.log().error("命令", "/buddy move sleep 异常", error);
                Bukkit.getScheduler().runTask(plugin, () ->
                        Text.send(player, "§c睡觉时出错了：" + Json.rootMessage(error)));
                return;
            }
            JsonObject json = Json.parse(result);
            String message = Json.getString(json, "message", Json.getString(json, "error", "完成"));
            Bukkit.getScheduler().runTask(plugin, () -> Text.send(player, "§7" + message));
        });
    }

    private void eat(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Bot bot = requireBot(player);
        if (bot == null || blockedByDowned(player, bot)) {
            return;
        }
        Material material = null;
        if (args.length > 1) {
            material = Material.matchMaterial(args[1].toUpperCase(Locale.ROOT));
            if (material == null || !material.isEdible()) {
                Text.send(player, "§c无效的食物名：" + args[1]);
                return;
            }
        }
        Text.send(player, "§7让他吃点东西……");
        plugin.botActions().eat(bot, material, true).whenComplete((result, error) -> {
            if (error != null) {
                plugin.log().error("命令", "/buddy do eat 异常", error);
                Bukkit.getScheduler().runTask(plugin, () ->
                        Text.send(player, "§c吃东西出错了：" + Json.rootMessage(error)));
                return;
            }
            JsonObject json = Json.parse(result);
            String message = Json.getString(json, "message", Json.getString(json, "error", "完成"));
            Bukkit.getScheduler().runTask(plugin, () -> Text.send(player, "§7" + message));
        });
    }

    private void persona(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("show")) {
            boolean custom = plugin.profiles().hasCustomPersona(player);
            String text = plugin.profiles().persona(player);
            if (text == null || text.isBlank()) {
                Text.send(player, "§7当前人格：§f未设置（使用插件默认行为）。");
            } else {
                Text.send(player, "§7当前人格（" + (custom ? "个人" : "全局默认") + "）：§f" + text);
            }
            Text.send(player, "§7设置：§f/buddy ai persona set <人格描述> §7| 清除：§f/buddy ai persona reset");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("reset") || action.equals("clear")) {
            plugin.profiles().clearPersona(player);
            Text.send(player, "§a已清除个人人格，将使用全局默认人格。");
            return;
        }
        String text;
        if (action.equals("set")) {
            if (args.length < 3) {
                Text.send(player, "§7用法：§f/buddy ai persona set <人格描述>");
                return;
            }
            text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        } else {
            text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        }
        plugin.profiles().setPersona(player, text);
        Text.send(player, "§a人格已保存到 §fplayers/" + player.getUniqueId() + "/persona.txt");
        Text.send(player, "§7当前人格：§f" + text);
    }

    private void ai(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("set") && args.length < 3) {
            Text.send(player, "§7用法：§f/buddy ai backend <deepseek|astrbot|auto>");
            Text.send(player, "§7default = DeepSeek（可操作假人），astrbot = 纯聊天，auto = 自动");
            return;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("set")) {
            String value = args[2].toLowerCase(Locale.ROOT);
            String backend = switch (value) {
                case "default", "deepseek", "ds" -> "deepseek";
                case "astrbot", "ab" -> "astrbot";
                case "auto" -> "auto";
                default -> null;
            };
            if (backend == null) {
                Text.send(player, "§c用法：§f/buddy ai backend <deepseek|astrbot|auto>");
                return;
            }
            plugin.ai().setPreference(player, backend);
            Text.send(player, "§a默认 AI 已设为：§f" + backendLabel(backend));

            ResolvedAi resolved = plugin.ai().resolve(player);
            if (resolved.backend() == AiBackend.DEEPSEEK && !resolved.validated()) {
                Text.send(player, "§7正在后台验证 " + resolved.keySource().label() + "……");
                plugin.ai().resolveValidated(player).whenComplete((validated, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error != null || !validated.deepSeek()) {
                                Text.send(player, "§c验证失败："
                                        + (error != null ? Json.rootMessage(error) : validated.reason()));
                            } else {
                                Text.send(player, "§a验证通过，可以直接聊天指挥我了。");
                            }
                        }));
                return;
            }
            Text.send(player, "§7当前实际使用：§f" + resolved.backend().label()
                    + " §7（Key 来源：§f" + resolved.keySource().label() + "§7）");
            if (!resolved.reason().isBlank()) {
                Text.send(player, "§e" + resolved.reason());
            }
            return;
        }
        aiStatus(player);
    }

    /**
     * {@code /buddy ai backend <auto|deepseek|astrbot>}：选择默认后端。
     */
    private void aiBackend(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Text.send(sender, "§7用法：§f/buddy ai backend <auto|deepseek|astrbot>");
            Text.send(sender, "§7deepseek = 可操作假人，astrbot = 纯聊天，auto = 自动选择");
            return;
        }
        ai(sender, new String[]{"ai", "set", args[1]});
    }

    private void aiStatus(Player player) {
        ResolvedAi resolved = plugin.ai().resolve(player);
        ApiConfig own = plugin.ai().ownApi(plugin.profiles().getOrCreate(player));
        ApiConfig global = plugin.ai().globalApi();
        AstrBotConfig astr = plugin.profiles().astrBotConfigFor(player);
        boolean astrUsable = astr != null && astr.usable();

        Text.send(player, "§8§m----------§r §bAI 后端 §8§m----------");
        if (own != null) {
            Text.send(player, "§7DeepSeek 个人 Key：§f" + own.maskedKey()
                    + " §7模型 §f" + own.model()
                    + " §7" + (plugin.profiles().isValidated(player) ? "§a已验证" : "§c未验证"));
            Text.send(player, "§8  地址：" + plugin.client().endpointOf(own));
        } else if (global != null) {
            Text.send(player, "§7DeepSeek：§f使用服务器全局 Key §7模型 §f" + global.model()
                    + " §7" + (resolved.validated() ? "§a已验证" : "§e未验证"));
            Text.send(player, "§8  地址：" + plugin.client().endpointOf(global));
        } else {
            Text.send(player, "§7DeepSeek：§c未配置 §8（/buddy ai set <apikey>）");
            Text.send(player, "§8  默认模型 §f" + AiDefaults.MODEL
                    + " §8默认地址 §f" + AiDefaults.BASE_URL);
        }
        Text.send(player, "§7AstrBot：§f" + (astrUsable ? "可用" : "不可用")
                + (astr != null && astrUsable
                        ? " §7地址 §f" + astr.baseUrl() + " §7会话 §f" + astr.mode() : ""));
        Text.send(player, "§7你的偏好：§f" + backendLabel(resolved.preference())
                + " §7（服务器默认 §f" + backendLabel(plugin.settings().aiBackend()) + "§7）");
        Text.send(player, "§7实际使用：§f" + resolved.backend().label()
                + (resolved.usable() ? " §7Key 来源 §f" + resolved.keySource().label() : ""));
        if (!resolved.reason().isBlank()) {
            Text.send(player, "§e" + resolved.reason());
        }
        if (resolved.needsChoice()) {
            Text.send(player, "§f/buddy ai backend deepseek §7（DeepSeek，可操作假人）");
            Text.send(player, "§f/buddy ai backend astrbot §7（AstrBot，纯聊天）");
        }
        String lastError = plugin.client().lastError();
        if (lastError != null && !lastError.isBlank()) {
            Text.send(player, "§c最近一次 DeepSeek 失败：§7" + oneLine(lastError));
        }
        String astrError = plugin.astrBot().lastError();
        if (astrError != null && !astrError.isBlank()) {
            Text.send(player, "§c最近一次 AstrBot 失败：§7" + oneLine(astrError));
        }
        if (!resolved.usable()) {
            Text.send(player, "§7提示：没有 AI 也可以创建假人，"
                    + "用 §f/buddy move follow §7、§f/buddy do eat §7、§f/buddy status §7等命令操作它。");
        }
        Text.send(player, "§7切换：§f/buddy ai backend <deepseek|astrbot|auto>");
    }

    private static String backendLabel(String backend) {
        if (backend == null) {
            return "未设置";
        }
        return switch (backend.toLowerCase(Locale.ROOT)) {
            case "deepseek" -> "DeepSeek";
            case "astrbot" -> "AstrBot";
            case "auto" -> "自动";
            default -> backend;
        };
    }

    private void astrBot(CommandSender sender, String[] args) {
        if (args.length < 2) {
            astrBotStatus(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "set" -> astrBotSet(sender, args);
            case "on" -> astrBotToggle(sender, true);
            case "off" -> astrBotToggle(sender, false);
            case "test" -> astrBotTest(sender);
            case "status" -> astrBotStatus(sender);
            case "session" -> astrBotSession(sender, args);
            case "clear" -> astrBotClear(sender);
            case "say", "chat", "ask" -> astrBotSay(sender, args);
            default -> {
                Text.send(sender, "§c用法：§f/buddy astrbot set <base-url> <api-key> [session-mode]");
                Text.send(sender, "§7其他：§f/buddy astrbot on|off|test|status|session|clear|say <内容>");
            }
        }
    }

    private void astrBotSet(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 4) {
            Text.send(player, "§7用法：§f/buddy astrbot set <base-url> <api-key> [per-player|fixed|per-message]");
            Text.send(player, "§7例如：§f/buddy astrbot set http://127.0.0.1:6185 abk_xxxxx per-player");
            return;
        }
        String baseUrl = args[2].trim();
        String apiKey = args[3].trim();
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            Text.send(player, "§cbase-url 必须以 http:// 或 https:// 开头。");
            return;
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String mode = args.length > 4 ? args[4].toLowerCase(Locale.ROOT) : "per-player";
        if (!mode.equals("per-player") && !mode.equals("fixed") && !mode.equals("per-message")) {
            Text.send(player, "§c会话模式只能是 per-player、fixed 或 per-message。");
            return;
        }

        PlayerProfile profile = plugin.profiles().getOrCreate(player);
        profile.setAstrBotBaseUrl(baseUrl);
        profile.setAstrBotKey(apiKey);
        profile.setAstrBotEnabled(true);
        profile.setAstrBotSessionMode(mode);
        plugin.profiles().save(profile);

        Text.send(player, "§a你的 AstrBot 配置已保存到 §fplayers/" + player.getUniqueId()
                + "/config.yml §7（key: " + maskKey(apiKey) + "，会话: " + mode + "）");
        Text.send(player, "§7正在测试连接……");
        AstrBotConfig config = plugin.profiles().astrBotConfigFor(player);
        plugin.astrBot().ask(config, "mc-plugin", "mc-plugin-test", "连接测试：请用一句话证明你在线。")
                .whenComplete((reply, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        Text.send(player, "§c连接失败：" + Json.rootMessage(error));
                    } else {
                        Text.send(player, "§a连接成功：§f" + oneLine(reply));
                    }
                }));
    }

    private void astrBotToggle(CommandSender sender, boolean enabled) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        PlayerProfile profile = plugin.profiles().getOrCreate(player);
        if (enabled && (profile.getAstrBotKey().isBlank() || profile.getAstrBotBaseUrl().isBlank())) {
            Text.send(player, "§c你还没有配置自己的 AstrBot：§f/buddy astrbot set <base-url> <api-key>");
            Text.send(player, "§7也可以让管理员用 §f/buddy global astrbot set ... §7配置服务器默认值。");
            return;
        }
        profile.setAstrBotEnabled(enabled);
        plugin.profiles().save(profile);
        Text.send(player, "§a你的 AstrBot 已" + (enabled ? "启用" : "关闭") + "。");
    }

    private void astrBotTest(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        AstrBotConfig config = plugin.profiles().astrBotConfigFor(player);
        if (!config.usable()) {
            Text.send(player, "§cAstrBot 还没有配置或未启用：§f/buddy astrbot set <base-url> <api-key>");
            return;
        }
        Text.send(player, "§7正在向 AstrBot 发送测试消息……");
        plugin.astrBot().ask(config, "mc-plugin", "mc-plugin-test", "连接测试：请用一句话证明你在线。")
                .whenComplete((reply, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        Text.send(player, "§c测试失败：" + Json.rootMessage(error));
                    } else {
                        Text.send(player, "§aAstrBot 回复：§f" + oneLine(reply));
                    }
                }));
    }

    private void astrBotStatus(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        AstrBotConfig config = plugin.profiles().astrBotConfigFor(player);
        boolean own = plugin.profiles().hasOwnAstrBot(player);
        Text.send(player, "§8§m----------§r §bAstrBot（个人） §8§m----------");
        Text.send(player, "§7状态：§f" + (config.usable() ? "可用" : "不可用")
                + " §7（" + (own ? "使用你自己的 Key" : "使用服务器全局 Key") + "）");
        Text.send(player, "§7地址：§f" + config.baseUrl());
        Text.send(player, "§7API Key：§f" + config.maskedKey());
        Text.send(player, "§7会话模式：§f" + config.mode());
        Text.send(player, "§7超时：§f" + config.timeoutSeconds() + " 秒"
                + " §7最大回复：§f" + config.maxReplyChars() + " 字符");
        String session = plugin.astrBot().sessionIdFor(config, player);
        Text.send(player, "§7你的会话：§f" + (session == null ? "（每次新建）" : session));
        Text.send(player, "§7设置：§f/buddy astrbot set <base-url> <api-key> [session-mode]");
        Text.send(player, "§7其他：§f/buddy astrbot on|off|test|session|clear|say <内容>");
    }

    private void astrBotSession(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 3) {
            Text.send(player, "§7当前会话模式：§f" + plugin.profiles().astrBotConfigFor(player).mode());
            Text.send(player, "§7用法：§f/buddy astrbot session <per-player|fixed|per-message> [fixed-session-id]");
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        if (!mode.equals("per-player") && !mode.equals("fixed") && !mode.equals("per-message")) {
            Text.send(player, "§c会话模式只能是 per-player、fixed 或 per-message。");
            return;
        }
        PlayerProfile profile = plugin.profiles().getOrCreate(player);
        profile.setAstrBotSessionMode(mode);
        if (args.length > 3) {
            profile.setAstrBotFixedSession(args[3]);
        }
        plugin.profiles().save(profile);
        Text.send(player, "§a你的会话模式已设为 §f" + mode + "§a。");
    }

    private void astrBotClear(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        PlayerProfile profile = plugin.profiles().getOrCreate(player);
        profile.setAstrBotBaseUrl("");
        profile.setAstrBotKey("");
        profile.setAstrBotEnabled(false);
        profile.setAstrBotSessionMode(null);
        profile.setAstrBotFixedSession(null);
        plugin.profiles().save(profile);
        Text.send(player, "§a已清除你的 AstrBot 配置。");
    }

    private void astrBotSay(CommandSender sender, String[] args) {
        if (!sender.hasPermission("leavesdeepseek.use")) {
            Text.send(sender, "§c你没有权限使用 AstrBot 对话。");
            return;
        }
        Player player = sender instanceof Player p ? p : null;
        if (!plugin.astrBot().enabled(player)) {
            Text.send(sender, "§cAstrBot 还没有配置：§f/buddy astrbot set <base-url> <api-key>");
            return;
        }
        if (args.length < 3) {
            Text.send(sender, "§7用法：§f/buddy astrbot say <内容>");
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (plugin.settings().chatLogToConsole()) {
            plugin.getLogger().info("[AstrBot] " + (player == null ? "控制台" : player.getName())
                    + " -> " + oneLine(message));
        }
        Text.send(sender, "§7正在问 AstrBot……");
        plugin.astrBot().ask(player, message)
                .whenComplete((reply, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        Text.send(sender, "§cAstrBot 调用失败：" + Json.rootMessage(error));
                    } else {
                        if (plugin.settings().chatLogToConsole()) {
                            plugin.getLogger().info("[AstrBot] -> " + oneLine(reply));
                        }
                        Text.send(sender, "§bAstrBot §7» §f" + oneLine(reply));
                    }
                }));
    }

    private void global(CommandSender sender, String[] args) {
        if (!sender.hasPermission("leavesdeepseek.admin")) {
            Text.send(sender, "§c你没有权限修改全局配置。");
            return;
        }
        if (args.length < 2) {
            globalStatus(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadPlugin();
                Text.send(sender, "§a全局配置已重载。");
            }
            case "debug" -> globalDebug(sender, args);
            case "ai" -> globalAi(sender, args);
            case "astrbot" -> globalAstrBot(sender, args);
            case "set" -> globalSet(sender, args);
            case "clean" -> globalClean(sender);
            default -> {
                Text.send(sender, "§c用法：§f/buddy global <set|clean|reload|debug|ai|astrbot>");
                Text.send(sender, "§7全局配置只能通过 /buddy global 修改，玩家个人设置请用 /buddy astrbot、/buddy ai。");
            }
        }
    }

    private void globalStatus(CommandSender sender) {
        AstrBotConfig config = globalAstrBotConfig();
        ApiConfig deepSeek = plugin.ai().globalApi();
        Text.send(sender, "§8§m----------§r §b全局配置 §8§m----------");
        Text.send(sender, "§7配置版本：§f" + plugin.getConfig().getInt("config-version", 1));
        Text.send(sender, "§7默认 AI 后端：§f" + backendLabel(plugin.settings().aiBackend()));
        Text.send(sender, "§7调试日志：§f" + (plugin.settings().debug() ? "开" : "关"));
        Text.send(sender, "§7DeepSeek 全局 Key：§f" + (deepSeek == null
                ? "未配置（" + (plugin.settings().useGlobalConfig() ? "玩家需用自己的 Key" : "已禁止全局 Key") + "）"
                : deepSeek.maskedKey() + " §7" + (plugin.ai().isGlobalValidated(deepSeek) ? "§a已验证" : "§e未验证")));
        if (deepSeek != null) {
            Text.send(sender, "§7  模型：§f" + deepSeek.model() + " §7地址：§f" + plugin.client().endpointOf(deepSeek));
        } else {
            Text.send(sender, "§7  默认模型：§f" + plugin.settings().model()
                    + " §7默认地址：§f" + plugin.settings().baseUrl());
        }
        Text.send(sender, "§7AstrBot 默认：§f" + (config.usable() ? "可用" : "不可用"));
        Text.send(sender, "§7地址：§f" + config.baseUrl()
                + " §7Key：§f" + config.maskedKey()
                + " §7会话：§f" + config.mode());
        Text.send(sender, "§7修改：§f/buddy global set <apikey> [model] [base-url] §7| §f/buddy global clean");
        Text.send(sender, "§7       §f/buddy global astrbot set <base-url> <api-key> [session-mode]");
        Text.send(sender, "§7       §f/buddy global ai set <auto|deepseek|astrbot>");
        Text.send(sender, "§7       §f/buddy global debug on|off §7| §f/buddy global reload");
        Text.send(sender, "§7玩家的个人 Key / AstrBot / AI 偏好保存在 §fplayers/<uuid>/config.yml§7，优先于全局。");
    }

    /**
     * 设置服务器全局 DeepSeek Key：写进插件根目录 config.yml 的 deepseek.*，
     * 只允许管理员通过 /buddy global 修改。
     * <p>用法：/buddy global set &lt;apikey&gt; [model] [base-url]，/buddy global clean 恢复默认。</p>
     */
    private void globalSet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Text.send(sender, "§7用法：§f/buddy global set <apikey> [model] [base-url]");
            Text.send(sender, "§7model / base-url 不填或填 §fdefault§7，表示沿用 config.yml 里现有的 deepseek.* 值。");
            Text.send(sender, "§7填写 base-url 时必须同时填写 model。");
            Text.send(sender, "§7清空并恢复默认：§f/buddy global clean");
            return;
        }

        String key = args[2].trim();
        if (key.equalsIgnoreCase("clear") || key.equalsIgnoreCase("none")) {
            globalClean(sender);
            return;
        }
        if (key.isEmpty() || key.equalsIgnoreCase("default")) {
            Text.send(sender, "§cAPI Key 不能为空（要清除请用 §f/buddy global clean§c）。");
            return;
        }

        String model = args.length > 3 ? args[3].trim() : "default";
        String baseUrl = args.length > 4 ? args[4].trim() : "default";

        if (args.length == 4 && looksLikeUrl(model)) {
            Text.send(sender, "§c如果填写 base-url，必须同时填写 model：");
            Text.send(sender, "§f/buddy global set <apikey> <model> <base-url>");
            return;
        }
        boolean defaultModel = PlayerProfiles.isDefault(model);
        boolean defaultBaseUrl = PlayerProfiles.isDefault(baseUrl);
        if (!defaultBaseUrl && defaultModel) {
            Text.send(sender, "§c填写 base-url 时必须同时填写 model。");
            return;
        }
        if (!defaultBaseUrl && !looksLikeUrl(baseUrl)) {
            Text.send(sender, "§cbase-url 必须以 http:// 或 https:// 开头。");
            return;
        }

        String previousKey = plugin.settings().apiKey();
        boolean wasEnabled = plugin.settings().useGlobalConfig();
        plugin.getConfig().set("deepseek.api-key", key);
        if (!defaultModel) {
            plugin.getConfig().set("deepseek.model", model);
        }
        if (!defaultBaseUrl) {
            plugin.getConfig().set("deepseek.base-url", PlayerProfiles.normalizeBaseUrl(baseUrl));
        }
        if (!wasEnabled) {
            // 填了全局 Key 却因为开关关闭不生效会很困惑，这里自动打开并告知
            plugin.getConfig().set("deepseek.use-global-config", true);
        }
        plugin.saveConfig();
        plugin.reloadPlugin();

        ApiConfig api = plugin.ai().globalApi();
        if (api == null) {
            Text.send(sender, "§c配置不完整，请检查 api key / model / base-url。");
            return;
        }
        if (!wasEnabled) {
            Text.send(sender, "§e已自动打开 §fdeepseek.use-global-config§e（否则全局 Key 不会生效）。");
        }
        if (!previousKey.equals(key)) {
            // 换了 Key，旧的验证结果作废
            plugin.ai().clearGlobalValidation();
        }
        plugin.log().info("配置", "管理员 " + sender.getName() + " 更新了全局 DeepSeek Key（模型 "
                + api.model() + "，地址 " + plugin.client().endpointOf(api) + "）");
        Text.send(sender, "§7已写入全局配置，正在验证 Key（模型 " + api.model() + "，地址 "
                + plugin.client().endpointOf(api) + "）……");
        plugin.client().validate(api).whenComplete((ignored, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.ai().setGlobalValidated(api, false);
                        Text.send(sender, "§c验证失败：" + Json.rootMessage(error));
                        Text.send(sender, "§7Key 已保存但标记为无效，检查后重新执行 /buddy global set。");
                        return;
                    }
                    plugin.ai().setGlobalValidated(api, true);
                    Text.send(sender, "§a服务器全局 DeepSeek Key 验证通过！模型：§f" + api.model()
                            + " §7地址：§f" + api.baseUrl());
                    Text.send(sender, "§7没有个人 Key 的玩家现在会自动使用它（§f/buddy global§7 可查看）。");
                }));
    }

    /**
     * 清空服务器全局 DeepSeek 配置并恢复默认值：
     * Key 为空、模型 {@code deepseek-chat}、地址 {@code https://api.deepseek.com}。
     */
    private void globalClean(CommandSender sender) {
        plugin.getConfig().set("deepseek.api-key", AiDefaults.API_KEY);
        plugin.getConfig().set("deepseek.model", AiDefaults.MODEL);
        plugin.getConfig().set("deepseek.base-url", AiDefaults.BASE_URL);
        plugin.saveConfig();
        plugin.ai().clearGlobalValidation();
        plugin.reloadPlugin();
        plugin.log().info("配置", "管理员 " + sender.getName() + " 清空了服务器全局 DeepSeek 配置（恢复默认值）");
        Text.send(sender, "§a已清空服务器全局 DeepSeek 配置：");
        Text.send(sender, "§7  API Key：§f空 §7| 模型：§f" + AiDefaults.MODEL
                + " §7| 地址：§f" + AiDefaults.BASE_URL);
        Text.send(sender, "§7没有个人 Key 的玩家需要自己执行 §f/buddy ai set <apikey>§7。");
    }

    private void globalDebug(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Text.send(sender, "§7调试日志：§f" + (plugin.settings().debug() ? "开" : "关"));
            Text.send(sender, "§7用法：§f/buddy global debug on|off");
            return;
        }
        boolean enabled = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
        plugin.getConfig().set("debug", enabled);
        plugin.saveConfig();
        plugin.reloadPlugin();
        Text.send(sender, "§7全局调试日志已" + (enabled ? "开启" : "关闭") + "。");
    }

    private void globalAi(CommandSender sender, String[] args) {
        if (args.length < 4 || !args[2].equalsIgnoreCase("set")) {
            Text.send(sender, "§7当前全局默认后端：§f" + backendLabel(plugin.settings().aiBackend()));
            Text.send(sender, "§7用法：§f/buddy global ai set <auto|deepseek|astrbot>");
            return;
        }
        String value = args[3].toLowerCase(Locale.ROOT);
        String backend = switch (value) {
            case "auto", "deepseek", "astrbot" -> value;
            default -> null;
        };
        if (backend == null) {
            Text.send(sender, "§c只能是 auto、deepseek 或 astrbot。");
            return;
        }
        plugin.getConfig().set("ai.backend", backend);
        plugin.saveConfig();
        plugin.reloadPlugin();
        Text.send(sender, "§a全局默认 AI 后端已设为 §f" + backendLabel(backend) + "§a。");
    }

    private void globalAstrBot(CommandSender sender, String[] args) {
        if (args.length < 3) {
            globalAstrBotStatus(sender);
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "set" -> globalAstrBotSet(sender, args);
            case "on" -> globalAstrBotToggle(sender, true);
            case "off" -> globalAstrBotToggle(sender, false);
            case "test" -> globalAstrBotTest(sender);
            case "status" -> globalAstrBotStatus(sender);
            case "session" -> globalAstrBotSession(sender, args);
            case "clear" -> globalAstrBotClear(sender);
            default -> {
                Text.send(sender, "§c用法：§f/buddy global astrbot <set|on|off|test|status|session|clear>");
            }
        }
    }

    private void globalAstrBotSet(CommandSender sender, String[] args) {
        if (args.length < 5) {
            Text.send(sender, "§7用法：§f/buddy global astrbot set <base-url> <api-key> [per-player|fixed|per-message]");
            return;
        }
        String baseUrl = args[3].trim();
        String apiKey = args[4].trim();
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            Text.send(sender, "§cbase-url 必须以 http:// 或 https:// 开头。");
            return;
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String mode = args.length > 5 ? args[5].toLowerCase(Locale.ROOT) : "per-player";
        if (!mode.equals("per-player") && !mode.equals("fixed") && !mode.equals("per-message")) {
            Text.send(sender, "§c会话模式只能是 per-player、fixed 或 per-message。");
            return;
        }
        plugin.getConfig().set("astrbot.base-url", baseUrl);
        plugin.getConfig().set("astrbot.key", apiKey);
        plugin.getConfig().set("astrbot.enabled", true);
        plugin.getConfig().set("astrbot.session-mode", mode);
        plugin.saveConfig();
        plugin.reloadPlugin();

        Text.send(sender, "§a全局 AstrBot 默认配置已保存：§f" + baseUrl
                + " §7（key: " + maskKey(apiKey) + "，会话: " + mode + "）");
        Text.send(sender, "§7正在测试连接……");
        AstrBotConfig config = globalAstrBotConfig();
        plugin.astrBot().ask(config, "mc-plugin", "mc-plugin-test", "连接测试：请用一句话证明你在线。")
                .whenComplete((reply, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        Text.send(sender, "§c连接失败：" + Json.rootMessage(error));
                    } else {
                        Text.send(sender, "§a连接成功：§f" + oneLine(reply));
                    }
                }));
    }

    private void globalAstrBotToggle(CommandSender sender, boolean enabled) {
        if (enabled && (plugin.settings().astrBotKey().isBlank() || plugin.settings().astrBotBaseUrl().isBlank())) {
            Text.send(sender, "§c请先配置全局 AstrBot：§f/buddy global astrbot set <base-url> <api-key>");
            return;
        }
        plugin.getConfig().set("astrbot.enabled", enabled);
        plugin.saveConfig();
        plugin.reloadPlugin();
        Text.send(sender, "§a全局 AstrBot 已" + (enabled ? "启用" : "关闭") + "。");
    }

    private void globalAstrBotTest(CommandSender sender) {
        AstrBotConfig config = globalAstrBotConfig();
        if (!config.usable()) {
            Text.send(sender, "§c全局 AstrBot 还没有配置或未启用。");
            return;
        }
        Text.send(sender, "§7正在向 AstrBot 发送测试消息……");
        plugin.astrBot().ask(config, "mc-plugin", "mc-plugin-test", "连接测试：请用一句话证明你在线。")
                .whenComplete((reply, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        Text.send(sender, "§c测试失败：" + Json.rootMessage(error));
                    } else {
                        Text.send(sender, "§aAstrBot 回复：§f" + oneLine(reply));
                    }
                }));
    }

    private void globalAstrBotStatus(CommandSender sender) {
        AstrBotConfig config = globalAstrBotConfig();
        Text.send(sender, "§8§m----------§r §bAstrBot（全局） §8§m----------");
        Text.send(sender, "§7状态：§f" + (config.usable() ? "可用" : "不可用"));
        Text.send(sender, "§7地址：§f" + config.baseUrl());
        Text.send(sender, "§7API Key：§f" + config.maskedKey());
        Text.send(sender, "§7会话模式：§f" + config.mode());
        Text.send(sender, "§7超时：§f" + config.timeoutSeconds() + " 秒"
                + " §7最大回复：§f" + config.maxReplyChars() + " 字符");
        Text.send(sender, "§7设置：§f/buddy global astrbot set <base-url> <api-key> [session-mode]");
        Text.send(sender, "§7其他：§f/buddy global astrbot on|off|test|session|clear|status");
    }

    private void globalAstrBotSession(CommandSender sender, String[] args) {
        if (args.length < 4) {
            Text.send(sender, "§7当前全局会话模式：§f" + plugin.settings().astrBotSessionMode());
            Text.send(sender, "§7用法：§f/buddy global astrbot session <per-player|fixed|per-message> [fixed-session-id]");
            return;
        }
        String mode = args[3].toLowerCase(Locale.ROOT);
        if (!mode.equals("per-player") && !mode.equals("fixed") && !mode.equals("per-message")) {
            Text.send(sender, "§c会话模式只能是 per-player、fixed 或 per-message。");
            return;
        }
        plugin.getConfig().set("astrbot.session-mode", mode);
        if (args.length > 4) {
            plugin.getConfig().set("astrbot.fixed-session", args[4]);
        }
        plugin.saveConfig();
        plugin.reloadPlugin();
        Text.send(sender, "§a全局会话模式已设为 §f" + mode + "§a。");
    }

    private void globalAstrBotClear(CommandSender sender) {
        plugin.getConfig().set("astrbot.key", "");
        plugin.getConfig().set("astrbot.enabled", false);
        plugin.saveConfig();
        plugin.reloadPlugin();
        Text.send(sender, "§a已清除全局 AstrBot 的 API Key 并关闭对接。");
    }

    private AstrBotConfig globalAstrBotConfig() {
        return plugin.ai().globalAstrBot();
    }

    private static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "未配置";
        }
        String value = key.trim();
        if (value.length() <= 10) {
            return "****";
        }
        return value.substring(0, 6) + "****" + value.substring(value.length() - 4);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace("\r", "").replace("\n", " ").trim();
    }

    private void listen(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length > 1) {
            String value = args[1].toLowerCase(Locale.ROOT);
            if (value.equals("on") || value.equals("true")) {
                plugin.chatListener().setListening(player, true);
                Text.send(player, "§a现在你直接说话，我就会收到（不需要 /buddy do task 或 @）。");
                return;
            }
            if (value.equals("off") || value.equals("false")) {
                plugin.chatListener().setListening(player, false);
                Text.send(player, "§7已改为仅 §f@ §7或假人名开头时触发。");
                return;
            }
        }
        boolean listening = plugin.chatListener().isListening(player);
        Text.send(player, "§7当前聊天模式：§f" + (listening ? "直听（说每句话都会发给伙伴）" : "仅 @ 触发"));
        Text.send(player, "§7切换：§f/buddy do listen on §7或 §f/buddy do listen off");
    }

    private void say(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Bot bot = requireBot(player);
        if (bot == null) {
            return;
        }
        if (args.length < 2) {
            Text.send(player, "§7用法：§f/buddy do say <内容>");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (plugin.settings().chatLogToConsole()) {
            plugin.getLogger().info("[对话] " + BotNames.rawName(bot) + " 说：" + oneLine(text));
        }
        bot.chat(text);
    }

    private void task(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (!player.hasPermission("leavesdeepseek.use")) {
            Text.send(player, "§c你没有权限使用 AI 对话。");
            return;
        }
        Bot bot = requireBot(player);
        if (bot == null || blockedByDowned(player, bot)) {
            return;
        }
        if (args.length < 2) {
            Text.send(player, "§7用法：§f/buddy do task <要做的事>");
            return;
        }

        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        long cooldown = plugin.settings().chatCooldownSeconds() * 1000L;
        if (now - last < cooldown) {
            Text.send(player, "§e慢一点，我还没想好上一个问题呢。");
            return;
        }
        cooldowns.put(player.getUniqueId(), now);

        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        player.sendActionBar(Text.component("§b" + BotNames.rawName(bot) + " 正在思考..."));
        boolean logToConsole = plugin.settings().chatLogToConsole();
        if (logToConsole) {
            plugin.getLogger().info("[对话] " + player.getName() + " -> " + BotNames.rawName(bot)
                    + "（命令）：" + oneLine(message));
        }
        plugin.agent().ask(bot, player, message).whenComplete((reply, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    player.sendActionBar(Text.component(""));
                    if (error != null) {
                        plugin.log().error("对话", "AgentService 抛出异常（命令触发）", error);
                        Text.send(player, "§c" + BotNames.rawName(bot) + " 处理失败：" + Json.rootMessage(error));
                        return;
                    }
                    if (logToConsole) {
                        plugin.getLogger().info("[对话] " + BotNames.rawName(bot) + " -> " + player.getName()
                                + "：" + oneLine(reply));
                    }
                    Text.send(player, "§b" + BotNames.rawName(bot) + " §7» §f" + reply);
                }));
    }

    private void stop(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Bot bot = requireBot(player);
        if (bot == null) {
            return;
        }
        plugin.stopBot(bot, "主人用 /buddy move stop 停止了所有动作");
        Text.send(player, "§7已经停下，跟随也关了（想让它再跟上：说「跟着我」或 §f/buddy move follow§7）。");
    }

    private void status(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Bot bot = requireBot(player);
        if (bot == null) {
            return;
        }
        JsonObject state = BotSnapshot.capture(bot, player);
        Text.send(player, "§8§m----------§r §b" + BotNames.rawName(bot) + " §8§m----------");
        Text.send(player, "§7位置：§f" + Json.getString(state, "position", "?")
                + " §7世界：§f" + Json.getString(state, "world", "?"));
        Text.send(player, "§7血量：§f" + Json.getDouble(state, "health", 0)
                + " §7饥饿：§f" + Json.getInt(state, "food", 0)
                + " §7距离主人：§f" + Json.getDouble(state, "owner_distance", 0));
        Text.send(player, "§7主手：§f" + Json.getString(state, "held_item", "empty"));
        Text.send(player, "§7动作：§f" + plugin.bots().actionNames(bot));
        if (plugin.bots().isDowned(bot)) {
            Text.send(player, "§c状态：§f倒地（物品已保留，说「复活」或 §f/buddy bot revive§c）");
        }
        Text.send(player, "§7跟随：§f" + (plugin.navigator().isFollowDisabled(bot)
                ? "§7已关闭（说「跟着我」或 /buddy move follow 重新开启）"
                : (plugin.navigator().isFollowing(bot) ? "§a开启" : "§7未开启")));
        ResolvedAi stateAi = plugin.ai().resolve(player);
        Text.send(player, "§7AI 后端：§f" + stateAi.backend().label()
                + " §7（偏好：§f" + backendLabel(stateAi.preference())
                + " §7Key：§f" + stateAi.keySource().label() + "§7）");
        if (plugin.navigator().isNavigating(bot)) {
            Text.send(player, "§7导航中：§a是");
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("leavesdeepseek.admin")) {
            Text.send(sender, "§c你没有权限重载配置。");
            return;
        }
        plugin.reloadPlugin();
        Text.send(sender, "§a配置已重载。");
    }

    private void debug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("leavesdeepseek.admin")) {
            Text.send(sender, "§c你没有权限。");
            return;
        }
        if (args.length > 1) {
            Text.send(sender, "§7全局调试开关请使用：§f/buddy global debug on|off");
            return;
        }
        Text.send(sender, "§7假人总数：§f" + plugin.bots().allBots().size());
        Text.send(sender, "§7已注册工具：§f" + plugin.tools().all().size());
        if (sender instanceof Player player) {
            ApiConfig own = plugin.ai().ownApi(plugin.profiles().getOrCreate(player));
            Text.send(sender, "§7你的 API Key：§f" + (own != null
                    ? own.maskedKey() + " §7（" + (plugin.profiles().isValidated(player) ? "已验证" : "未验证") + "）"
                    : "未配置"));
        }
        Text.send(sender, "§7全局 API Key：§f"
                + (plugin.settings().apiKey() != null && !plugin.settings().apiKey().isBlank() ? "已配置" : "未配置"));
        Text.send(sender, "§7默认模型：§f" + plugin.settings().model());
        Text.send(sender, "§7AstrBot（全局）：§f" + (globalAstrBotConfig().usable() ? "可用" : "不可用")
                + " §7地址：§f" + plugin.settings().astrBotBaseUrl()
                + " §7会话：§f" + plugin.settings().astrBotSessionMode());
        if (sender instanceof Player player) {
            ResolvedAi dbg = plugin.ai().resolve(player);
            Text.send(sender, "§7你的 AI 后端：§f" + dbg.backend().label()
                    + " §7（Key：§f" + dbg.keySource().label() + "§7）");
        }
        Text.send(sender, "§7调试日志：§f" + (plugin.settings().debug() ? "开" : "关")
                + " §7（/buddy debug on|off 切换）");
    }

    private Player asPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        Text.send(sender, "§c该命令只能由玩家执行。");
        return null;
    }

    private Bot requireBot(Player player) {
        Bot bot = plugin.bots().primaryBot(player);
        if (bot == null) {
            Text.send(player, "§e你还没有伙伴，先用 §f/buddy bot spawn §e创建一个。");
        }
        return bot;
    }

    /**
     * 倒地的伙伴不能执行动作，只提示主人先复活。
     */
    private boolean blockedByDowned(Player player, Bot bot) {
        if (bot != null && plugin.bots().isDowned(bot)) {
            Text.send(player, "§c" + BotNames.rawName(bot)
                    + " 倒下了，先说「复活」或执行 §f/buddy bot revive§c 把他救起来。");
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefix(SUB_COMMANDS, args[0]);
        }
        String group = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (group) {
                case "bot" -> {
                    return prefix(List.of("spawn", "remove", "list", "revive", "info"), args[1]);
                }
                case "move" -> {
                    return prefix(List.of("follow", "come", "stay", "stop", "sleep"), args[1]);
                }
                case "do" -> {
                    return prefix(List.of("task", "say", "eat", "listen"), args[1]);
                }
                case "ai" -> {
                    return prefix(List.of("set", "clean", "backend", "status", "persona"), args[1]);
                }
                case "astrbot" -> {
                    return prefix(List.of("set", "on", "off", "test", "status", "session", "clear", "say"), args[1]);
                }
                case "global" -> {
                    return prefix(List.of("set", "clean", "reload", "debug", "ai", "astrbot"), args[1]);
                }
                case "debug" -> {
                    return prefix(List.of("on", "off"), args[1]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (group.equals("bot") && args.length == 3 && args[1].equalsIgnoreCase("remove")
                && sender instanceof Player player) {
            List<String> names = new ArrayList<>();
            for (Bot bot : plugin.bots().botsOf(player)) {
                names.add(BotNames.rawName(bot));
            }
            return names;
        }
        if (group.equals("do") && args.length == 3) {
            if (args[1].equalsIgnoreCase("eat")) {
                return List.of("COOKED_BEEF", "BREAD", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "GOLDEN_CARROT");
            }
            if (args[1].equalsIgnoreCase("listen")) {
                return prefix(List.of("on", "off"), args[2]);
            }
        }
        if (group.equals("ai") && args.length == 3) {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "backend" -> {
                    return prefix(List.of("auto", "deepseek", "astrbot"), args[2]);
                }
                case "persona" -> {
                    return prefix(List.of("set", "show", "reset"), args[2]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (group.equals("astrbot") && args.length == 3 && args[1].equalsIgnoreCase("session")) {
            return List.of("per-player", "fixed", "per-message");
        }
        if (group.equals("astrbot") && args[1].equalsIgnoreCase("set") && args.length == 5) {
            return List.of("per-player", "fixed", "per-message");
        }
        if (group.equals("global") && args.length == 3 && args[1].equalsIgnoreCase("set")) {
            return List.of("clear");
        }
        if (group.equals("global") && args.length == 3 && args[1].equalsIgnoreCase("debug")) {
            return List.of("on", "off");
        }
        if (group.equals("global") && args.length == 3 && args[1].equalsIgnoreCase("ai")) {
            return List.of("set");
        }
        if (group.equals("global") && args.length == 4
                && args[1].equalsIgnoreCase("ai") && args[2].equalsIgnoreCase("set")) {
            return List.of("auto", "deepseek", "astrbot");
        }
        if (group.equals("global") && args.length == 3 && args[1].equalsIgnoreCase("astrbot")) {
            return List.of("set", "on", "off", "test", "status", "session", "clear");
        }
        if (group.equals("global") && args.length == 4
                && args[1].equalsIgnoreCase("astrbot") && args[2].equalsIgnoreCase("session")) {
            return List.of("per-player", "fixed", "per-message");
        }
        if (group.equals("global") && args.length == 6
                && args[1].equalsIgnoreCase("astrbot") && args[2].equalsIgnoreCase("set")) {
            return List.of("per-player", "fixed", "per-message");
        }
        return List.of();
    }

    private static List<String> prefix(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
