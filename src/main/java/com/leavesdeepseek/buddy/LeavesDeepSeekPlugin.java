package com.leavesdeepseek.buddy;

import com.leavesdeepseek.buddy.ai.AgentService;
import com.leavesdeepseek.buddy.ai.AgentTool;
import com.leavesdeepseek.buddy.ai.AiResolver;
import com.leavesdeepseek.buddy.ai.AstrBotClient;
import com.leavesdeepseek.buddy.ai.DeepSeekClient;
import com.leavesdeepseek.buddy.ai.ToolRegistry;
import com.leavesdeepseek.buddy.bot.ArmorKeeper;
import com.leavesdeepseek.buddy.bot.AutoEater;
import com.leavesdeepseek.buddy.bot.BotActions;
import com.leavesdeepseek.buddy.bot.BotNames;
import com.leavesdeepseek.buddy.bot.BotNavigator;
import com.leavesdeepseek.buddy.bot.BotService;
import com.leavesdeepseek.buddy.bot.BotStateService;
import com.leavesdeepseek.buddy.bot.CombatGuardian;
import com.leavesdeepseek.buddy.bot.IdleWanderer;
import com.leavesdeepseek.buddy.command.BuddyCommand;
import com.leavesdeepseek.buddy.config.PlayerProfiles;
import com.leavesdeepseek.buddy.config.PluginSettings;
import com.leavesdeepseek.buddy.listener.BotListener;
import com.leavesdeepseek.buddy.listener.ChatListener;
import com.leavesdeepseek.buddy.tools.ArmorTools;
import com.leavesdeepseek.buddy.tools.BlockTools;
import com.leavesdeepseek.buddy.tools.AstrBotTools;
import com.leavesdeepseek.buddy.tools.BotTools;
import com.leavesdeepseek.buddy.tools.ChatTools;
import com.leavesdeepseek.buddy.tools.CombatTools;
import com.leavesdeepseek.buddy.tools.CraftingTools;
import com.leavesdeepseek.buddy.tools.InfoTools;
import com.leavesdeepseek.buddy.tools.MovementTools;
import com.leavesdeepseek.buddy.tools.SleepTools;
import com.leavesdeepseek.buddy.util.BuddyLog;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.leavesmc.leaves.entity.bot.Bot;

import java.nio.file.Path;
import java.util.ArrayList;

/**
 * LeavesDeepSeek 主类：把 DeepSeek 的 Function Calling 与 Leaves 的假人 API 连接起来。
 */
public final class LeavesDeepSeekPlugin extends JavaPlugin {

    private PluginSettings settings;
    private BuddyLog log;
    private PlayerProfiles profiles;
    private BotService bots;
    private BotNavigator navigator;
    private BotActions botActions;
    private ToolRegistry tools;
    private DeepSeekClient client;
    private AstrBotClient astrBot;
    private AiResolver ai;
    private AgentService agent;
    private ChatListener chatListener;
    private IdleWanderer idleWanderer;
    private CombatGuardian combatGuardian;
    private AutoEater autoEater;
    private ArmorKeeper armorKeeper;
    private BotStateService botStates;

    private volatile boolean shuttingDown;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        this.settings = new PluginSettings(this);
        this.log = new BuddyLog(logDirectory(), settings.logEnabled(), settings.logCapturePayload(),
                settings.logMaxBodyChars(), settings.logRetainDays(), settings.logMaxFileMb());
        this.log.info("启动", "LeavesDeepSeek 正在启用（版本 " + getPluginMeta().getVersion() + "）");
        if (settings.logEnabled()) {
            getLogger().info("插件日志：" + log.currentFilePath());
        }

        if (!BotService.leavesAvailable()) {
            getLogger().severe("当前服务端不是 LeavesMC，缺少 org.leavesmc.leaves.entity.bot.BotManager，插件将停用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.bots = new BotService(this, settings);
        this.profiles = new PlayerProfiles(this, settings);
        this.navigator = new BotNavigator(this, settings, bots);
        this.navigator.start();
        this.botActions = new BotActions(this, settings, bots, navigator);

        this.idleWanderer = new IdleWanderer(this);
        this.idleWanderer.start();

        this.combatGuardian = new CombatGuardian(this);
        this.combatGuardian.start();
        getServer().getPluginManager().registerEvents(combatGuardian, this);

        this.botStates = new BotStateService(this);

        this.tools = new ToolRegistry(this, settings);
        registerTools();

        this.client = new DeepSeekClient(this, settings);
        this.astrBot = new AstrBotClient(this);
        this.ai = new AiResolver(this, settings, profiles);
        this.agent = new AgentService(this, settings, tools, client, profiles, ai);

        BuddyCommand command = new BuddyCommand(this);
        PluginCommand buddy = getCommand("buddy");
        if (buddy != null) {
            buddy.setExecutor(command);
            buddy.setTabCompleter(command);
        } else {
            getLogger().severe("plugin.yml 中缺少 buddy 命令，插件功能将不完整。");
        }

        this.chatListener = new ChatListener(this);
        getServer().getPluginManager().registerEvents(chatListener, this);
        getServer().getPluginManager().registerEvents(new BotListener(this), this);

        bots.loadBindings();
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
            profiles.getOrCreate(online);
        }

        this.autoEater = new AutoEater(this);
        this.autoEater.start();
        this.armorKeeper = new ArmorKeeper(this);
        this.armorKeeper.start();
        this.botStates.start();

        getLogger().info("LeavesDeepSeek 已启用，共注册 " + tools.all().size() + " 个 AI 工具。");
        boolean globalKey = ai.globalApi() != null;
        if (globalKey) {
            getLogger().info("已检测到服务器全局 DeepSeek Key：没有个人 Key 的玩家将使用它。");
        } else {
            getLogger().info("未配置服务器全局 DeepSeek Key，玩家需用 /buddy set <apikey> 配置个人 Key。");
        }
        if (ai.globalAstrBot().usable()) {
            getLogger().info("已检测到服务器全局 AstrBot 配置：" + ai.globalAstrBot().baseUrl());
        }
    }

    /**
     * 旧配置迁移。
     * <ul>
     *   <li>v1 -> v2：战斗半径旧默认值 12/100 改为 10/50（只改仍是旧默认值的项）；</li>
     *   <li>v2 -> v3：{@code deepseek.allow-global-api-key} 更名为 {@code deepseek.use-global-config}；</li>
     *   <li>v3 -> v4：砍树改用 {@code chop_tree}，{@code safety.log-leaf-radius} 旧默认值 5 改为 3。</li>
     *   <li>v4 -> v5：{@code combat.defense-radius} 旧默认值 10 改为 15（苦力怕改为主动远离）。</li>
     *   <li>v5 -> v6：新增 {@code survival.emergency-eat-health}（血量 ≤ 该值时强制进食）。</li>
     * </ul>
     */
    private void migrateConfig() {
        org.bukkit.configuration.file.FileConfiguration config = getConfig();
        int version = config.getInt("config-version", 1);
        boolean changed = false;

        if (version < 2) {
            if (config.getInt("combat.defense-radius", 10) == 12) {
                config.set("combat.defense-radius", 10);
            }
            if (config.getInt("combat.defend-owner-radius", 50) == 100) {
                config.set("combat.defend-owner-radius", 50);
            }
            config.set("config-version", 2);
            changed = true;
            getLogger().info("config.yml 已升级到 v2：护主半径 50 格、自动索敌半径 10 格。");
            version = 2;
        }

        if (version < 3) {
            if (config.contains("deepseek.allow-global-api-key")) {
                config.set("deepseek.use-global-config",
                        config.getBoolean("deepseek.allow-global-api-key", true));
                config.set("deepseek.allow-global-api-key", null);
            } else if (!config.contains("deepseek.use-global-config")) {
                config.set("deepseek.use-global-config", true);
            }
            config.set("config-version", 3);
            changed = true;
            getLogger().info("config.yml 已升级到 v3：deepseek.allow-global-api-key → deepseek.use-global-config。");
            version = 3;
        }

        if (version < 4) {
            // 树判定改为「底部是泥土 + 树顶有树叶」，树叶搜索半径 5 → 3
            if (!config.isSet("safety.log-leaf-radius")
                    || config.getInt("safety.log-leaf-radius", 4) >= 5) {
                config.set("safety.log-leaf-radius", 3);
            }
            config.set("config-version", 4);
            changed = true;
            getLogger().info("config.yml 已升级到 v4：砍树改用 chop_tree，树叶判定半径 5 → 3（防止误拆村民房子）。");
            version = 4;
        }

        if (version < 5) {
            // 自动索敌半径旧默认值 10 → 15（只改仍是旧默认值的项）
            if (config.getInt("combat.defense-radius", 15) == 10) {
                config.set("combat.defense-radius", 15);
            }
            config.set("config-version", 5);
            changed = true;
            getLogger().info("config.yml 已升级到 v5：自动索敌半径 10 → 15 格（15 格内主动疾跑清怪，苦力怕改为主动远离）。");
            version = 5;
        }

        if (version < 6) {
            // 低血量强制进食（新增项，缺省时补上默认值）
            if (!config.isSet("survival.emergency-eat-health")) {
                config.set("survival.emergency-eat-health", 5);
            }
            config.set("config-version", 6);
            changed = true;
            getLogger().info("config.yml 已升级到 v6：新增 survival.emergency-eat-health（血量 ≤5 强制进食）。");
        }

        if (changed) {
            saveConfig();
        }
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (botStates != null) {
            botStates.stop();
        }
        if (autoEater != null) {
            autoEater.stop();
        }
        if (armorKeeper != null) {
            armorKeeper.stop();
        }
        if (agent != null) {
            agent.close();
        }
        if (combatGuardian != null) {
            combatGuardian.stop();
        }
        if (botActions != null) {
            botActions.stopAllFights();
        }
        if (idleWanderer != null) {
            idleWanderer.stop();
        }
        if (navigator != null) {
            navigator.shutdown();
        }
        // /stop 收尾：botStates.stop() 已经把最后一次存档写好（含死亡快照），
        // 这里再清空假人背包。否则 Leaves 移除假人时会把装备丢在地上，
        // 掉落物会随区块一起存盘，重启后和存档里的装备重复成两套。
        if (botStates != null && bots != null && botStates.enabled()) {
            for (Bot bot : new ArrayList<>(bots.allBots())) {
                if (bots.ownerOf(bot) != null) {
                    botStates.clearInventory(bot);
                }
            }
        }
        if (settings != null && settings.removeOnDisable() && bots != null) {
            for (Bot bot : new ArrayList<>(bots.allBots())) {
                if (bots.ownerOf(bot) != null) {
                    if (settings.saveOnRemove() && botStates != null) {
                        botStates.saveAndClear(bot);
                    }
                    bots.remove(bot, settings.saveOnRemove());
                }
            }
        }
        if (client != null) {
            client.close();
        }
        if (astrBot != null) {
            astrBot.close();
        }
        if (log != null) {
            log.info("启动", "LeavesDeepSeek 正在停用");
            log.close();
        }
        getLogger().info("LeavesDeepSeek 已停用。");
    }

    private void registerTools() {
        for (AgentTool tool : new MovementTools(this).all()) {
            tools.register(tool);
        }
        for (AgentTool tool : new BlockTools(this).all()) {
            tools.register(tool);
        }
        for (AgentTool tool : new CombatTools(this).all()) {
            tools.register(tool);
        }
        for (AgentTool tool : new InfoTools(this).all()) {
            tools.register(tool);
        }
        for (AgentTool tool : new CraftingTools(this).all()) {
            tools.register(tool);
        }
        for (AgentTool tool : new SleepTools(this).all()) {
            tools.register(tool);
        }
        for (AgentTool tool : new ChatTools(this).all()) {
            tools.register(tool);
        }
        for (AgentTool tool : new AstrBotTools(this).all()) {
            tools.register(tool);
        }
        for (AgentTool tool : new BotTools(this).all()) {
            tools.register(tool);
        }
        for (AgentTool tool : new ArmorTools(this).all()) {
            tools.register(tool);
        }
    }

    public void reloadPlugin() {
        settings.reload();
        if (log != null) {
            log.reconfigure(settings.logEnabled(), settings.logCapturePayload(),
                    settings.logMaxBodyChars(), settings.logRetainDays(), settings.logMaxFileMb());
            log.info("配置", "配置已重载");
        }
        if (profiles != null) {
            profiles.reload();
        }
        if (bots != null) {
            bots.applySleepSettings();
        }
        if (idleWanderer != null) {
            idleWanderer.stop();
            idleWanderer.start();
        }
        if (combatGuardian != null) {
            combatGuardian.stop();
            combatGuardian.start();
        }
        if (autoEater != null) {
            autoEater.stop();
            autoEater.start();
        }
        if (armorKeeper != null) {
            armorKeeper.stop();
            armorKeeper.start();
        }
        if (botStates != null) {
            botStates.reload();
        }
        getLogger().info("配置已重新加载。");
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * 停止假人的全部动作并关闭跟随。
     * <p>`/buddy stop` 和聊天里说「停下」共用这一套逻辑，保证两条入口行为完全一致：
     * 打断挖掘/放置/砍树/战斗、取消导航、关闭粘性跟随（之后只有「跟着我」才会重新打开）。</p>
     */
    public void stopBot(Bot bot, String reason) {
        if (bot == null) {
            return;
        }
        if (navigator != null) {
            navigator.stopFollow(bot);
        }
        if (botActions != null) {
            botActions.interrupt(bot, reason == null ? "主人让你停下" : reason);
        } else if (bots != null) {
            bots.stopAllActions(bot);
        }
        if (log != null) {
            log.info("命令", "停止全部动作 | 假人=" + BotNames.rawName(bot)
                    + " | 原因=" + (reason == null ? "-" : reason));
        }
    }

    public BotStateService botStates() {
        return botStates;
    }

    public PlayerProfiles profiles() {
        return profiles;
    }

    public PluginSettings settings() {
        return settings;
    }

    /** 插件自有日志（写 plugins/LeavesDeepSeek/logs/）。 */
    public BuddyLog log() {
        return log;
    }

    private Path logDirectory() {
        return getDataFolder().toPath().resolve("logs");
    }

    public BotService bots() {
        return bots;
    }

    public BotNavigator navigator() {
        return navigator;
    }

    public BotActions botActions() {
        return botActions;
    }

    public ToolRegistry tools() {
        return tools;
    }

    public DeepSeekClient client() {
        return client;
    }

    public AstrBotClient astrBot() {
        return astrBot;
    }

    public AgentService agent() {
        return agent;
    }

    public ChatListener chatListener() {
        return chatListener;
    }

    public CombatGuardian combatGuardian() {
        return combatGuardian;
    }

    public ArmorKeeper armorKeeper() {
        return armorKeeper;
    }

    /** AI 配置解析中心（偏好 / Key 来源 / 验证状态）。 */
    public AiResolver ai() {
        return ai;
    }
}
