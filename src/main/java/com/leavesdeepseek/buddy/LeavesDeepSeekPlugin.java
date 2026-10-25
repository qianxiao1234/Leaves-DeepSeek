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
import com.leavesdeepseek.buddy.bot.WaterGuard;
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
import com.leavesdeepseek.buddy.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
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
    private WaterGuard waterGuard;
    private BotStateService botStates;
    private com.leavesdeepseek.agent.AgentConfig agentConfig;
    private com.leavesdeepseek.agent.BodyManager bodyManager;

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
        this.waterGuard = new WaterGuard(this);
        this.waterGuard.start();
        this.botStates.start();

        // ---- AI 躯壳子系统（独立于传统假人；见 ai_body_SPEC.md）----
        this.agentConfig = new com.leavesdeepseek.agent.AgentConfig(this);
        this.bodyManager = new com.leavesdeepseek.agent.BodyManager(this, agentConfig);
        getServer().getPluginManager().registerEvents(new com.leavesdeepseek.agent.AgentEvents(this), this);
        com.leavesdeepseek.agent.command.AICommand aiCommand = new com.leavesdeepseek.agent.command.AICommand(this);
        PluginCommand aiBody = getCommand("ai");
        if (aiBody != null) {
            aiBody.setExecutor(aiCommand);
            aiBody.setTabCompleter(aiCommand);
        }
        if (agentConfig.enabled()) {
            try {
                com.leavesdeepseek.agent.AgentServer server = new com.leavesdeepseek.agent.AgentServer(
                        this, agentConfig, bodyManager);
                bodyManager.server(server);
                server.boot();
                getLogger().info("AI 躯壳已启用：ws://" + agentConfig.bind() + ":" + agentConfig.port()
                        + "/agent  token=" + agentConfig.token());
            } catch (Throwable throwable) {
                getLogger().severe("AI 躯壳服务启动失败（端口被占用？）：" + throwable.getMessage());
            }
        } else {
            getLogger().info("AI 躯壳未启用（config.yml -> agent.enabled=false）。");
        }
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (bodyManager != null) {
                bodyManager.tick();
            }
        }, 20L, 1L);

        getLogger().info("LeavesDeepSeek 已启用，共注册 " + tools.all().size() + " 个 AI 工具。");
        boolean globalKey = ai.globalApi() != null;
        if (globalKey) {
            getLogger().info("已检测到服务器全局 DeepSeek Key：没有个人 Key 的玩家将使用它。");
        } else {
            getLogger().info("未配置服务器全局 DeepSeek Key，玩家需用 /buddy ai set <apikey> 配置个人 Key。");
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
     *   <li>v6 -> v7：{@code deepseek.max-tokens} 旧默认值 1024 提到 4096（推理型模型需要更多输出额度）。</li>
     *   <li>v7 -> v8：{@code companion.auto-follow} 旧默认值 true 改为 false（不再默认跟随主人）。</li>
     *   <li>v8 -> v9：新增 {@code agent:} / {@code body:} 两段（AI 躯壳子系统，默认关闭）。</li>
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
            version = 6;
        }

        if (version < 7) {
            // 推理型本地模型（R1/QwQ）会把 max_tokens 花在 reasoning 上，
            // 1024 常常导致「有思考、无内容、无工具调用」，旧默认值统一提到 4096
            if (config.getInt("deepseek.max-tokens", 4096) == 1024) {
                config.set("deepseek.max-tokens", 4096);
            }
            config.set("config-version", 7);
            changed = true;
            getLogger().info("config.yml 已升级到 v7：deepseek.max-tokens 默认 1024 → 4096"
                    + "（推理型模型需要更多输出额度）。");
            version = 7;
        }

        if (version < 8) {
            // 「创建后自动跟随」旧默认值是 true，导致每次创建/唤醒假人都黏在主人身上。
            // 新默认改为 false；旧配置文件里仍是 true 的一律关掉（想自动跟随请手动改回 true）。
            if (config.getBoolean("companion.auto-follow", false)) {
                config.set("companion.auto-follow", false);
                getLogger().info("config.yml 已升级到 v8：companion.auto-follow 默认改为 false（不再默认跟随）；"
                        + "想让它创建后自动跟随，把该项改回 true。");
            } else {
                getLogger().info("config.yml 已升级到 v8：companion.auto-follow 默认改为 false（不再默认跟随）。");
            }
            config.set("config-version", 8);
            changed = true;
        }

        if (version < 9) {
            // 新增 AI 躯壳子系统的配置段（缺失时补上，方便服主直接改）
            setIfMissing(config, "agent.enabled", false);
            setIfMissing(config, "agent.bind", "127.0.0.1");
            setIfMissing(config, "agent.port", 8123);
            setIfMissing(config, "agent.token", "");
            setIfMissing(config, "agent.max-bodies-per-session", 1);
            setIfMissing(config, "agent.auth-timeout-seconds", 5);
            setIfMissing(config, "agent.max-frame-bytes", 65536);
            setIfMissing(config, "agent.max-actions-per-second", 8);
            setIfMissing(config, "agent.max-sense-per-second", 4);
            setIfMissing(config, "agent.max-concurrent-tasks", 2);
            setIfMissing(config, "agent.chat-cooldown-ms", 2000);
            setIfMissing(config, "agent.idle-to-paused-seconds", 90);
            setIfMissing(config, "agent.paused-to-offline-seconds", 300);
            setIfMissing(config, "agent.auto-respawn", false);
            setIfMissing(config, "agent.respawn-delay-seconds", 0);
            setIfMissing(config, "agent.audit", true);
            setIfMissing(config, "agent.sense.motion-hz", 5);
            setIfMissing(config, "agent.sense.terrain-hz", 2);
            setIfMissing(config, "agent.sense.companion-hz", 1);
            setIfMissing(config, "agent.sense.task-hz", 5);
            setIfMissing(config, "agent.sense.close-radius", 16);
            setIfMissing(config, "agent.sense.nearby-radius", 48);
            setIfMissing(config, "agent.sense.landmark-max", 8);
            setIfMissing(config, "agent.sense.chat-radius", 64);
            setIfMissing(config, "body.max-bodies", 3);
            setIfMissing(config, "body.allow-pvp", false);
            setIfMissing(config, "body.allow-container", false);
            setIfMissing(config, "body.allow-trade", false);
            setIfMissing(config, "body.allow-drop-item", true);
            setIfMissing(config, "body.operate-radius", 48);
            setIfMissing(config, "body.auto-eat-threshold", 14);
            setIfMissing(config, "body.tps-pause-threshold", 15.0);
            setIfMissing(config, "body.tps-offline-threshold", 10.0);
            if (!config.isSet("body.blocked-materials")) {
                config.set("body.blocked-materials", java.util.List.of("BEDROCK", "BARRIER",
                        "END_PORTAL_FRAME", "COMMAND_BLOCK", "STRUCTURE_BLOCK", "JIGSAW"));
            }
            config.set("config-version", 9);
            changed = true;
            getLogger().info("config.yml 已升级到 v9：新增 agent:/body: 两段（AI 躯壳子系统，默认关闭）。");
            version = 9;
        }

        if (changed) {
            saveConfig();
        }
    }

    /**
     * 只在配置里没有这个键时写入默认值（迁移用，避免覆盖用户已改的值）。
     */
    private void setIfMissing(org.bukkit.configuration.file.FileConfiguration config,
                              String path, Object value) {
        if (!config.isSet(path)) {
            config.set(path, value);
        }
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (bodyManager != null) {
            bodyManager.shutdown();
        }
        if (botStates != null) {
            botStates.stop();
        }
        if (autoEater != null) {
            autoEater.stop();
        }
        if (armorKeeper != null) {
            armorKeeper.stop();
        }
        if (waterGuard != null) {
            waterGuard.stop();
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
        if (waterGuard != null) {
            waterGuard.stop();
            waterGuard.start();
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
     * <p>`/buddy move stop` 和聊天里说「停下」共用这一套逻辑，保证两条入口行为完全一致：
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

    /**
     * 主人下达「跟着我」：这是最高优先级指令，会立刻放下手上的活（挖掘/砍树/战斗/闲逛…）。
     * <p>唯一例外是**强制进食**（血量危急时插件自动吃最好的食物）：那一口必须先吃完，
     * 跟随请求会先记下来，进食结束的一瞬间自动接上。</p>
     *
     * @return true 表示已经开始跟随；false 表示因为强制进食被延后
     */
    public boolean followBot(Bot bot, Player owner, String reason) {
        double distance = settings == null ? 3.0 : settings.followDistance();
        return followBot(bot, owner, distance, reason);
    }

    /**
     * 同 {@link #followBot(Bot, Player, String)}，但可以指定跟随距离。
     * 唯一例外仍然是被强制进食延后。
     */
    public boolean followBot(Bot bot, Player owner, double distance, String reason) {
        if (bot == null || owner == null) {
            return false;
        }
        if (bots.isDowned(bot)) {
            // 倒地时只记住要跟随，复活后自动接上
            if (navigator != null) {
                navigator.deferForceFollow(bot, owner, distance);
            }
            return false;
        }
        if (combatGuardian != null && combatGuardian.isCreeperFleeing(bot)) {
            // 正被苦力怕追着跑：先活下来，安全 5 秒后自动接上跟随
            if (navigator != null) {
                navigator.deferForceFollow(bot, owner, distance);
            }
            if (log != null) {
                log.info("命令", "跟随延后（正在躲苦力怕） | 假人=" + BotNames.rawName(bot)
                        + " | 原因=" + (reason == null ? "-" : reason));
            }
            return false;
        }
        if (autoEater != null && autoEater.isForceEating(bot)) {
            // 不打断急救进食，但立刻停掉 AI 的其他工作，并把跟随挂起
            if (botActions != null) {
                botActions.yieldToFollow(bot, reason);
            }
            if (navigator != null) {
                navigator.deferForceFollow(bot, owner, distance);
            }
            if (log != null) {
                log.info("命令", "跟随延后（正在强制进食） | 假人=" + BotNames.rawName(bot)
                        + " | 原因=" + (reason == null ? "-" : reason));
            }
            return false;
        }
        if (botActions != null) {
            botActions.interrupt(bot, reason == null ? "主人让你跟上" : reason);
        }
        if (bot.isSleeping()) {
            // 睡觉时导航会直接跳过；主人叫跟上就先起床
            bot.wakeup(false);
        }
        if (navigator != null) {
            navigator.forceFollow(bot, owner, distance);
        }
        if (log != null) {
            log.info("命令", "开始跟随 | 假人=" + BotNames.rawName(bot)
                    + " | 原因=" + (reason == null ? "-" : reason));
        }
        return true;
    }

    /**
     * {@link #followBot} 返回 false 时的具体原因，方便聊天/命令给出准确文案。
     *
     * @return {@code downed} / {@code creeper} / {@code eating} / null
     */
    public String followDeferReason(Bot bot) {
        if (bot == null) {
            return null;
        }
        if (bots.isDowned(bot)) {
            return "downed";
        }
        if (combatGuardian != null && combatGuardian.isCreeperFleeing(bot)) {
            return "creeper";
        }
        if (autoEater != null && autoEater.isForceEating(bot)) {
            return "eating";
        }
        return null;
    }

    /**
     * AI 自己在当前请求里调用 {@code follow_owner} 时使用：只抢占移动/战斗，**不**增加动作世代，
     * 避免把自己的这次请求判成「旧请求」而丢掉最终回复。强制进食时同样只挂起、不打断。
     *
     * @return true 表示已经开始跟随
     */
    public boolean followBotFromAi(Bot bot, Player owner, double distance) {
        if (bot == null || owner == null) {
            return false;
        }
        if (bots.isDowned(bot)) {
            if (navigator != null) {
                navigator.deferForceFollow(bot, owner, distance);
            }
            return false;
        }
        if (combatGuardian != null && combatGuardian.isCreeperFleeing(bot)) {
            if (navigator != null) {
                navigator.deferForceFollow(bot, owner, distance);
            }
            return false;
        }
        if (autoEater != null && autoEater.isForceEating(bot)) {
            if (navigator != null) {
                navigator.deferForceFollow(bot, owner, distance);
            }
            return false;
        }
        if (botActions != null) {
            botActions.preempt(bot, "主人让你跟上");
        }
        if (bot.isSleeping()) {
            bot.wakeup(false);
        }
        if (navigator != null) {
            navigator.forceFollow(bot, owner, distance);
        }
        return true;
    }

    /**
     * 把倒地的伙伴救起来：解除无敌、回满血、清掉负面状态，并回到主人身边。
     * 物品在死亡时就被拦下了，这里不会动背包。
     *
     * @return true 表示已复活
     */
    public boolean reviveBot(Bot bot, Player owner) {
        if (bot == null || !bot.isValid()) {
            return false;
        }
        boolean wasDowned = bots.isDowned(bot);
        BotService.DownedState state = bots.downedState(bot);
        bots.clearDowned(bot);
        try {
            if (bot.isSleeping()) {
                bot.wakeup(false);
            }
            bot.setFireTicks(0);
            bot.setFallDistance(0.0f);
            bot.setInvulnerable(false);
            bot.setHealth(Math.max(1.0, bot.getMaxHealth()));
            bot.setFoodLevel(Math.max(6, bot.getFoodLevel()));
        } catch (RuntimeException exception) {
            getLogger().warning("复活假人失败（" + BotNames.rawName(bot) + "）：" + exception.getMessage());
        }
        if (owner != null && owner.isOnline()) {
            try {
                boolean sameWorld = bot.getWorld() != null && bot.getWorld().equals(owner.getWorld());
                double distance = sameWorld ? bot.getLocation().distance(owner.getLocation()) : Double.MAX_VALUE;
                if (!sameWorld || distance > 32.0) {
                    bot.teleport(owner.getLocation());
                }
            } catch (RuntimeException ignored) {
                // 跨维度 / 目标区块未加载时忽略，假人会继续留在原地
            }
        }
        if (state != null && state.wasFollowing() && owner != null && owner.isOnline()) {
            // 倒地前正在跟随：复活后直接接上
            followBot(bot, owner, "复活后继续跟随");
        }
        if (botStates != null) {
            botStates.save(bot);
        }
        if (log != null) {
            log.info("伙伴", "假人 " + BotNames.rawName(bot) + (wasDowned ? " 已被复活" : " 已恢复状态")
                    + "（物品保留）");
        }
        return true;
    }

    /**
     * 复活玩家的主要伙伴；如果假人已经不在线，会先尝试从存档重新生成。
     *
     * @return true 表示已复活或已开始从存档唤醒
     */
    public boolean revivePrimary(Player owner) {
        if (owner == null) {
            return false;
        }
        Bot bot = bots.primaryBot(owner);
        if (bot != null) {
            return reviveBot(bot, owner);
        }
        if (botStates == null || !botStates.hasState(owner.getUniqueId())) {
            return false;
        }
        if (!botStates.restoreSaved(owner.getUniqueId())) {
            return false;
        }
        // 皮肤是异步加载的：每 0.5 秒看一次，最多等 10 秒
        retryRevive(owner, 0);
        return true;
    }

    private void retryRevive(Player owner, int attempt) {
        if (isShuttingDown() || attempt > 20) {
            if (attempt > 20) {
                Text.send(owner, "§c没能从存档唤醒伙伴，请检查数据目录或使用 §f/buddy bot spawn§c 重新创建。");
            }
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Bot bot = bots.primaryBot(owner);
            if (bot != null && bot.isValid()) {
                reviveBot(bot, owner);
                Text.send(owner, "§a" + BotNames.rawName(bot) + " 已经站起来了（物品一件没少）。");
                return;
            }
            retryRevive(owner, attempt + 1);
        }, 10L);
    }

    public BotStateService botStates() {
        return botStates;
    }

    /** AI 躯壳子系统（外部机器人接管用）。 */
    public com.leavesdeepseek.agent.BodyManager bodyManager() {
        return bodyManager;
    }

    public com.leavesdeepseek.agent.AgentConfig agentConfig() {
        return agentConfig;
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

    /** 水域生存（缺氧上浮 / 主动上岸）。 */
    public WaterGuard waterGuard() {
        return waterGuard;
    }

    /** AI 配置解析中心（偏好 / Key 来源 / 验证状态）。 */
    public AiResolver ai() {
        return ai;
    }
}
