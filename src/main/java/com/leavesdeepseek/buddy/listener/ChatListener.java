package com.leavesdeepseek.buddy.listener;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.bot.BotNames;
import com.leavesdeepseek.buddy.bot.BotNavigator;
import com.leavesdeepseek.buddy.util.Json;
import com.leavesdeepseek.buddy.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 聊天监听：默认直接接管拥有伙伴的玩家的聊天；也可切换为仅 @ 触发。
 */
public final class ChatListener implements Listener {

    /** 「跟着我 / 过来 / 停下」这类高频指令走直通车道，不经过大模型 */
    private enum QuickIntent {
        FOLLOW, COME, STOP
    }

    private static final Pattern FOLLOW_PATTERN = Pattern.compile(
            "^(你|快|赶紧|马上|请)*(跟着我|跟上我|跟上|跟紧我|跟紧|跟随我|跟随|跟我来|跟我走|别走远|不要走远"
                    + "|follow|followme|comewithme)(啊|吧|呀|哦|嘛|呗|呢)*$");
    private static final Pattern COME_PATTERN = Pattern.compile(
            "^(你|快|赶紧|马上|请)*(过来|来我这|来我这里|来我身边|到这来|到我这来|来一下|来我这儿"
                    + "|come|comehere)(啊|吧|呀|哦|嘛|呗)*$");
    private static final Pattern STOP_PATTERN = Pattern.compile(
            "^(你|快|赶紧|请)*(停下|停下来|停一下|停手|先停|先停下|停|站住|别动|别走了|待着|待在原地|原地待着"
                    + "|别跟了|别跟着|别跟着我|不要跟着我|不要跟了|不用跟着我|不用跟着|不用跟了"
                    + "|不许跟着我|不许跟了|停止跟随|别追了|松开我|别干了|别挖了|别砍了"
                    + "|stop|stay|standstill)(了|啊|吧|呀|哦|嘛|呗|呢)*$");

    private final LeavesDeepSeekPlugin plugin;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    /** 玩家手动覆盖：true = 直听，false = 仅 @ 触发 */
    private final Map<UUID, Boolean> listenOverrides = new ConcurrentHashMap<>();
    /** 已经提醒过「还没配 AI」的玩家，避免每句话都弹一次提示 */
    private final java.util.Set<UUID> aiMissingWarned = ConcurrentHashMap.newKeySet();

    public ChatListener(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 当前玩家说话时，是否直接发给 AI。
     */
    public boolean isListening(Player player) {
        Boolean override = listenOverrides.get(player.getUniqueId());
        return override != null ? override : plugin.settings().chatAlwaysListen();
    }

    public void setListening(Player player, boolean listening) {
        listenOverrides.put(player.getUniqueId(), listening);
    }

    public void clear(UUID playerId) {
        listenOverrides.remove(playerId);
        cooldowns.remove(playerId);
        aiMissingWarned.remove(playerId);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.settings().chatEnabled()) {
            plugin.log().debug("聊天", "跳过：chat.enabled = false");
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("leavesdeepseek.use")) {
            plugin.log().debug("聊天", "跳过：" + player.getName() + " 没有 leavesdeepseek.use 权限");
            return;
        }
        Bot bot = plugin.bots().primaryBot(player);
        if (bot == null) {
            plugin.log().debug("聊天", "跳过：" + player.getName() + " 没有已绑定的假人"
                    + "（先 /buddy spawn，或重启后等待假人恢复完成）");
            return;
        }

        String message = Text.plain(event.message()).trim();
        if (message.isEmpty()) {
            plugin.log().debug("聊天", "跳过：消息为空");
            return;
        }

        boolean listening = isListening(player);
        String botName = BotNames.rawName(bot);
        String body = listening
                ? stripMention(message, botName)
                : extractCommand(message, botName);
        if (body == null || body.isEmpty()) {
            plugin.log().info("聊天", "跳过：非 @" + botName + " 触发（当前模式="
                    + (listening ? "直听" : "仅@触发") + "），原文=" + oneLine(message));
            return;
        }

        plugin.log().info("聊天", "收到 | 玩家=" + player.getName() + " | 假人=" + botName
                + " | 模式=" + (listening ? "直听" : "仅@触发")
                + " | 原文=" + oneLine(message)
                + " | 提交给AI=" + oneLine(body));
        if (plugin.settings().chatLogToConsole()) {
            plugin.getLogger().info("[对话] " + player.getName() + " -> " + botName + "：" + oneLine(body));
        }

        // 注意：不取消事件，主人说的话仍然公开可见；只有 AI 的回复默认只发回主人。

        // 高频指令直通：立刻执行，不等大模型（否则「跟着我」要排在上一次请求后面）
        if (handleQuickIntent(player, bot, body, botName)) {
            return;
        }

        // 没有可用 AI 时假人仍然存在，但聊天不触发大模型，避免每句话都回一句「还没配置 AI」刷屏。
        // 跟随/过来/停下这类直通指令在上面已经处理过了，不受影响。
        if (!plugin.ai().resolve(player).usable()) {
            plugin.log().info("聊天", "跳过：" + player.getName()
                    + " 没有可用的 AI（假人仍可用 /buddy 命令操作）");
            if (aiMissingWarned.add(player.getUniqueId())) {
                player.sendActionBar(Text.component(
                        "§e还没有配置 AI：/buddy set <apikey>，现在只能用 /buddy 命令控制假人"));
            }
            return;
        }

        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < plugin.settings().chatCooldownSeconds() * 1000L) {
            plugin.log().info("聊天", "跳过：冷却中（剩余 "
                    + (plugin.settings().chatCooldownSeconds() * 1000L - (now - last)) + " ms）");
            player.sendActionBar(Text.component("§e别急，我还在想上一个问题……"));
            return;
        }
        cooldowns.put(player.getUniqueId(), now);

        player.sendActionBar(Text.component("§b" + botName + " 正在思考..."));
        boolean logToConsole = plugin.settings().chatLogToConsole();
        plugin.log().info("聊天", "调用 AgentService.ask 开始");
        int epochAtAsk = plugin.botActions().epoch(bot);
        plugin.agent().ask(bot, player, body).whenComplete((reply, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // 先清掉「正在思考」，无论成功失败都不能把玩家卡在 thinking 状态
                    if (player.isOnline()) {
                        player.sendActionBar(Text.component(""));
                    }
                    // 这条请求已经被主人更新的指令取代（比如中途说了「跟着我」），不再回显旧结果
                    if (plugin.botActions().epoch(bot) != epochAtAsk) {
                        plugin.log().info("聊天", "旧请求已被新指令取代，丢弃这条回复");
                        return;
                    }
                    if (error != null) {
                        String reason = Json.rootMessage(error);
                        plugin.log().error("聊天", "AgentService 抛出异常：" + reason, error);
                        if (player.isOnline()) {
                            Text.send(player, "§c" + botName + " 处理失败：" + reason);
                        }
                        return;
                    }
                    String text = reply;
                    if (text == null || text.isBlank()) {
                        plugin.log().warn("聊天", "AI 返回空回复，已用兜底文本");
                        text = "我这边没想好怎么回答，你再说一次？";
                    }
                    plugin.log().info("聊天", "回复 | 假人=" + botName + " | 玩家=" + player.getName()
                            + " | 长度=" + text.length() + " | 内容=" + oneLine(text));
                    if (!player.isOnline()) {
                        plugin.log().info("聊天", "玩家已离线，回复未发送");
                        return;
                    }
                    if (logToConsole) {
                        plugin.getLogger().info("[对话] " + botName + " -> " + player.getName()
                                + "：" + oneLine(text));
                    }
                    if (plugin.settings().speakInChat()) {
                        bot.chat(text);
                    } else {
                        Text.send(player, "§b" + botName + " §7» §f" + text);
                    }
                }));
    }

    /**
     * 「跟着我 / 过来 / 停下」直通处理：第一时间打断旧任务并执行，不再排队等大模型。
     * <p>只匹配整句（去掉标点和空格后）等于这些说法的消息，
     * 所以「跟着我找张床睡觉呗」这种复合指令仍然交给 AI。</p>
     *
     * @return true 表示已经处理，调用方不要再走 AI
     */
    private boolean handleQuickIntent(Player player, Bot bot, String body, String botName) {
        String text = normalize(body);
        if (text.isEmpty() || text.length() > 14) {
            return false;
        }
        QuickIntent intent = matchIntent(text);
        if (intent == null) {
            return false;
        }
        plugin.log().info("聊天", "高优先级指令直通 | 玩家=" + player.getName() + " | 假人=" + botName
                + " | 指令=" + intent + " | 原文=" + oneLine(body));
        // 聊天事件在异步线程触发，所有 Bukkit 操作必须回到主线程
        try {
            Bukkit.getScheduler().runTask(plugin, () -> executeQuickIntent(player, bot, botName, intent));
        } catch (Throwable throwable) {
            plugin.log().warn("聊天", "无法调度直通指令：" + Json.rootMessage(throwable));
        }
        return true;
    }

    private void executeQuickIntent(Player player, Bot bot, String botName, QuickIntent intent) {
        try {
            switch (intent) {
                case FOLLOW -> {
                    plugin.botActions().interrupt(bot, "主人让你跟上");
                    plugin.navigator().forceFollow(bot, player, plugin.settings().followDistance());
                    reply(player, bot, botName, "好，跟着你呢。");
                }
                case COME -> {
                    plugin.botActions().interrupt(bot, "主人让你过去");
                    plugin.navigator().navigateAndWait(bot, player.getLocation(), 1.8, 20 * 60,
                                    BotNavigator.PRIORITY_HIGH)
                            .whenComplete((arrived, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                                if (error != null) {
                                    plugin.log().warn("聊天", "过来失败：" + Json.rootMessage(error));
                                    reply(player, bot, botName, "路上出错了，我尽量靠过去。");
                                    return;
                                }
                                reply(player, bot, botName, Boolean.TRUE.equals(arrived)
                                        ? "来了，就在你旁边。"
                                        : "路上被挡住了，我尽量靠近了。");
                            }));
                }
                case STOP -> {
                    // 与 /buddy stop 完全相同的一套逻辑：打断动作 + 关闭跟随
                    plugin.stopBot(bot, "主人在聊天里说「停下」");
                    reply(player, bot, botName, "好，停下了，也不再跟着你了。");
                }
                default -> {
                }
            }
        } catch (Throwable throwable) {
            plugin.log().error("聊天", "直通指令执行失败：" + intent, throwable);
        }
    }

    private static QuickIntent matchIntent(String text) {
        if (FOLLOW_PATTERN.matcher(text).matches()) {
            return QuickIntent.FOLLOW;
        }
        if (COME_PATTERN.matcher(text).matches()) {
            return QuickIntent.COME;
        }
        if (STOP_PATTERN.matcher(text).matches()) {
            return QuickIntent.STOP;
        }
        return null;
    }

    /**
     * 去掉空白、中英文标点并转小写，便于整句匹配。
     */
    private static String normalize(String message) {
        if (message == null) {
            return "";
        }
        return message.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、；：…～·「」『』（）【】〈〉《》“”‘’]", "");
    }

    private void reply(Player player, Bot bot, String botName, String text) {
        plugin.log().info("聊天", "回复 | 假人=" + botName + " | 玩家=" + player.getName()
                + " | 长度=" + text.length() + " | 内容=" + oneLine(text) + " | 来源=直通指令");
        if (!player.isOnline()) {
            return;
        }
        player.sendActionBar(Text.component(""));
        if (plugin.settings().speakInChat()) {
            bot.chat(text);
        } else {
            Text.send(player, "§b" + botName + " §7» §f" + text);
        }
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace("\r", "").replace("\n", " ").trim();
    }

    /**
     * 直听模式下，允许玩家仍然用 @ 或假人名开头；没有前缀则整句都发给 AI。
     */
    private String stripMention(String message, String botName) {
        String trigger = plugin.settings().chatTrigger();
        if (trigger != null && !trigger.isBlank() && message.startsWith(trigger)) {
            return message.substring(trigger.length()).trim();
        }
        String lower = message.toLowerCase(Locale.ROOT);
        String nameLower = botName.toLowerCase(Locale.ROOT);
        if (lower.startsWith(nameLower)) {
            return message.substring(botName.length()).replaceFirst("^[\\s:：,，]+", "").trim();
        }
        return message;
    }

    /**
     * 仅 @ 触发模式下，从聊天内容中提取指令；不是对伙伴说话时返回 null。
     */
    private String extractCommand(String message, String botName) {
        String trigger = plugin.settings().chatTrigger();
        if (trigger != null && !trigger.isBlank() && message.startsWith(trigger)) {
            return message.substring(trigger.length()).trim();
        }
        String lower = message.toLowerCase(Locale.ROOT);
        String nameLower = botName.toLowerCase(Locale.ROOT);
        if (lower.startsWith(nameLower)) {
            return message.substring(botName.length()).replaceFirst("^[\\s:：,，]+", "").trim();
        }
        return null;
    }
}
