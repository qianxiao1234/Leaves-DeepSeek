package com.leavesdeepseek.buddy.config;

import com.leavesdeepseek.buddy.ai.AiDefaults;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 插件配置的快照，reload 时整体重建。
 */
public final class PluginSettings {

    private final JavaPlugin plugin;

    private String apiKey;
    private String baseUrl;
    private String model;
    private double temperature;
    private int maxTokens;
    private int requestTimeoutSeconds;
    private int toolTimeoutSeconds;
    private int maxToolRounds;
    private int historyLimit;
    private String extraSystemPrompt;
    private boolean allowGlobalApiKey;

    private String namePrefix;
    private String defaultSkin;
    private int maxPerPlayer;
    private boolean autoFollow;
    private double followDistance;
    private boolean removeOnQuit;
    private boolean saveOnRemove;
    private boolean removeOnDisable;
    private boolean autoBindExisting;
    private boolean skipSleepCheck;

    private boolean chatEnabled;
    private boolean chatAlwaysListen;
    private String chatTrigger;
    private boolean speakInChat;
    private int chatCooldownSeconds;
    private boolean chatLogToConsole;

    private int operateRadius;
    private boolean allowBreakBlocks;
    private boolean allowPlaceBlocks;
    private boolean allowAttack;
    private boolean allowCreateBot;
    private boolean requireLeavesForLogs;
    private int logLeafRadius;
    private Set<Material> blockedMaterials;

    private int navMaxNodes;
    private int navMaxRange;
    private double navRepathDistance;
    private boolean teleportFallback;

    private boolean craftEnabled;
    private boolean craftRequireWorkbench;
    private int craftWorkbenchRadius;

    private boolean idleWanderEnabled;
    private int idleCheckIntervalSeconds;
    private double idleWanderChance;
    private double idleWanderRadius;
    private int idleWanderTimeoutSeconds;

    private boolean miningAutoPickup;
    private int miningPickupRadius;

    private boolean autoDefense;
    private int defenseRadius;
    private boolean defendOwner;
    private int defendOwnerRadius;
    private int combatCheckIntervalTicks;
    private Set<EntityType> avoidMobs;
    private int avoidRadius;

    private boolean autoEat;
    private int eatThreshold;
    private int eatCheckIntervalSeconds;
    private int emergencyEatHealth;

    private boolean persistEnabled;
    private int persistIntervalSeconds;
    private boolean restoreOnStart;

    private boolean astrBotEnabled;
    private String astrBotBaseUrl;
    private String astrBotKey;
    private String astrBotSessionMode;
    private String astrBotFixedSession;
    private int astrBotTimeoutSeconds;
    private int astrBotMaxReplyChars;

    private String aiBackend;

    private boolean debug;

    private boolean logEnabled;
    private boolean logCapturePayload;
    private int logMaxBodyChars;
    private int logRetainDays;
    private int logMaxFileMb;

    public PluginSettings(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        // 默认值全部走 AiDefaults：/buddy global clean 清空后与「配置项缺省」保持完全一致
        String key = config.getString("deepseek.api-key", AiDefaults.API_KEY);
        apiKey = key == null ? AiDefaults.API_KEY : key.trim();
        baseUrl = AiDefaults.baseUrlOrDefault(config.getString("deepseek.base-url", AiDefaults.BASE_URL));
        model = AiDefaults.modelOrDefault(config.getString("deepseek.model", AiDefaults.MODEL));
        temperature = config.getDouble("deepseek.temperature", 0.6);
        maxTokens = Math.max(64, config.getInt("deepseek.max-tokens", 4096));
        requestTimeoutSeconds = Math.max(10, config.getInt("deepseek.timeout-seconds", 60));
        toolTimeoutSeconds = Math.max(10, config.getInt("deepseek.tool-timeout-seconds", 90));
        maxToolRounds = Math.max(1, Math.min(20, config.getInt("deepseek.max-tool-rounds", 8)));
        historyLimit = Math.max(0, Math.min(100, config.getInt("deepseek.history-limit", 20)));
        extraSystemPrompt = config.getString("deepseek.extra-system-prompt", "");
        allowGlobalApiKey = config.getBoolean("deepseek.use-global-config",
                config.getBoolean("deepseek.allow-global-api-key", true));

        namePrefix = config.getString("companion.name-prefix", "AI_");
        defaultSkin = config.getString("companion.default-skin", "");
        maxPerPlayer = Math.max(1, config.getInt("companion.max-per-player", 1));
        autoFollow = config.getBoolean("companion.auto-follow", false);
        followDistance = Math.max(1.0, config.getDouble("companion.follow-distance", 3.0));
        removeOnQuit = config.getBoolean("companion.remove-on-quit", false);
        saveOnRemove = config.getBoolean("companion.save-on-remove", true);
        removeOnDisable = config.getBoolean("companion.remove-on-disable", false);
        autoBindExisting = config.getBoolean("companion.auto-bind-existing", true);
        skipSleepCheck = config.getBoolean("companion.skip-sleep-check", true);

        chatEnabled = config.getBoolean("chat.enabled", true);
        chatAlwaysListen = config.getBoolean("chat.always-listen", true);
        chatTrigger = config.getString("chat.trigger", "@");
        speakInChat = config.getBoolean("chat.speak-in-chat", false);
        chatCooldownSeconds = Math.max(0, config.getInt("chat.cooldown-seconds", 2));
        chatLogToConsole = config.getBoolean("chat.log-to-console", true);

        operateRadius = Math.max(4, config.getInt("safety.operate-radius", 48));
        allowBreakBlocks = config.getBoolean("safety.allow-break-blocks", true);
        allowPlaceBlocks = config.getBoolean("safety.allow-place-blocks", true);
        allowAttack = config.getBoolean("safety.allow-attack", true);
        allowCreateBot = config.getBoolean("safety.allow-create-bot", false);
        requireLeavesForLogs = config.getBoolean("safety.require-leaves-for-logs", true);
        logLeafRadius = Math.max(1, Math.min(12, config.getInt("safety.log-leaf-radius", 3)));
        blockedMaterials = parseMaterials(config.getStringList("safety.blocked-materials"));

        navMaxNodes = Math.max(200, Math.min(20000, config.getInt("navigator.max-nodes", 3000)));
        craftEnabled = config.getBoolean("crafting.enabled", true);
        craftRequireWorkbench = config.getBoolean("crafting.require-workbench", true);
        craftWorkbenchRadius = Math.max(2, Math.min(16, config.getInt("crafting.workbench-radius", 4)));

        navMaxRange = Math.max(8, Math.min(256, config.getInt("navigator.max-range", 64)));
        navRepathDistance = Math.max(1.0, config.getDouble("navigator.repath-distance", 3.0));
        teleportFallback = config.getBoolean("navigator.teleport-fallback", false);

        idleWanderEnabled = config.getBoolean("idle.enabled", true);
        idleCheckIntervalSeconds = Math.max(1, Math.min(120, config.getInt("idle.check-interval-seconds", 8)));
        idleWanderChance = Math.max(0.0, Math.min(1.0, config.getDouble("idle.chance", 0.35)));
        idleWanderRadius = Math.max(1.0, Math.min(8.0, config.getDouble("idle.radius", 2.0)));
        idleWanderTimeoutSeconds = Math.max(1, Math.min(30, config.getInt("idle.timeout-seconds", 4)));

        miningAutoPickup = config.getBoolean("mining.auto-pickup", true);
        miningPickupRadius = Math.max(1, Math.min(16, config.getInt("mining.pickup-radius", 5)));

        autoDefense = config.getBoolean("combat.auto-defense", true);
        defenseRadius = Math.max(3, Math.min(64, config.getInt("combat.defense-radius", 15)));
        defendOwner = config.getBoolean("combat.defend-owner", true);
        defendOwnerRadius = Math.max(4, Math.min(256, config.getInt("combat.defend-owner-radius", 50)));
        combatCheckIntervalTicks = Math.max(10, Math.min(100, config.getInt("combat.check-interval-ticks", 20)));
        if (config.isSet("combat.avoid-mobs")) {
            avoidMobs = parseEntityTypes(config.getStringList("combat.avoid-mobs"));
        } else {
            avoidMobs = Collections.emptySet();
        }
        avoidRadius = Math.max(4, Math.min(48, config.getInt("combat.avoid-radius", 12)));

        autoEat = config.getBoolean("survival.auto-eat", true);
        eatThreshold = Math.max(1, Math.min(19, config.getInt("survival.eat-threshold", 14)));
        eatCheckIntervalSeconds = Math.max(1, Math.min(60, config.getInt("survival.check-interval-seconds", 5)));
        emergencyEatHealth = Math.max(0, Math.min(20, config.getInt("survival.emergency-eat-health", 5)));

        persistEnabled = config.getBoolean("persistence.enabled", true);
        persistIntervalSeconds = Math.max(5, Math.min(600, config.getInt("persistence.interval-seconds", 30)));
        restoreOnStart = config.getBoolean("persistence.restore-on-start", true);

        astrBotEnabled = config.getBoolean("astrbot.enabled", false);
        astrBotBaseUrl = trimSlash(config.getString("astrbot.base-url", "http://127.0.0.1:6185"));
        String astrBotRawKey = config.getString("astrbot.key", "");
        astrBotKey = astrBotRawKey == null ? "" : astrBotRawKey.trim();
        String mode = config.getString("astrbot.session-mode", "per-player");
        mode = mode == null ? "per-player" : mode.trim().toLowerCase(Locale.ROOT);
        astrBotSessionMode = switch (mode) {
            case "fixed", "per-message", "per-player" -> mode;
            default -> "per-player";
        };
        astrBotFixedSession = config.getString("astrbot.fixed-session", "mc-server-main");
        astrBotTimeoutSeconds = Math.max(10, Math.min(300, config.getInt("astrbot.timeout-seconds", 120)));
        astrBotMaxReplyChars = Math.max(64, Math.min(4096, config.getInt("astrbot.max-reply-chars", 512)));

        String backend = config.getString("ai.backend", "auto");
        backend = backend == null ? "auto" : backend.trim().toLowerCase(Locale.ROOT);
        aiBackend = switch (backend) {
            case "deepseek", "astrbot" -> backend;
            default -> "auto";
        };

        debug = config.getBoolean("debug", false);

        logEnabled = config.getBoolean("logging.enabled", true);
        logCapturePayload = config.getBoolean("logging.capture-payload", true);
        logMaxBodyChars = Math.max(512, Math.min(200_000, config.getInt("logging.max-body-chars", 4000)));
        logRetainDays = Math.max(1, Math.min(365, config.getInt("logging.retain-days", 7)));
        logMaxFileMb = Math.max(1, Math.min(512, config.getInt("logging.max-file-mb", 8)));
    }

    private static String trimSlash(String value) {
        String result = value == null || value.isBlank() ? "http://127.0.0.1:6185" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static Set<EntityType> parseEntityTypes(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptySet();
        }
        Set<EntityType> result = EnumSet.noneOf(EntityType.class);
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                result.add(EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 忽略非法实体名
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static Set<Material> parseMaterials(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
                if (material != null) {
                    result.add(material);
                }
            } catch (RuntimeException ignored) {
                // 忽略非法配置项
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public String apiKey() {
        return apiKey;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model;
    }

    public double temperature() {
        return temperature;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public int requestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public int toolTimeoutSeconds() {
        return toolTimeoutSeconds;
    }

    public int maxToolRounds() {
        return maxToolRounds;
    }

    public int historyLimit() {
        return historyLimit;
    }

    public String extraSystemPrompt() {
        return extraSystemPrompt;
    }

    /**
     * 玩家没有自己的 Key 时，是否允许使用 config.yml 里的全局 Key。
     * 全局 Key 默认为空，所以打开这个开关本身不会带来任何开销。
     */
    public boolean useGlobalConfig() {
        return allowGlobalApiKey;
    }

    public String namePrefix() {
        return namePrefix;
    }

    public String defaultSkin() {
        return defaultSkin;
    }

    public int maxPerPlayer() {
        return maxPerPlayer;
    }

    public boolean autoFollow() {
        return autoFollow;
    }

    public double followDistance() {
        return followDistance;
    }

    public boolean removeOnQuit() {
        return removeOnQuit;
    }

    public boolean saveOnRemove() {
        return saveOnRemove;
    }

    public boolean removeOnDisable() {
        return removeOnDisable;
    }

    public boolean autoBindExisting() {
        return autoBindExisting;
    }

    public boolean skipSleepCheck() {
        return skipSleepCheck;
    }

    public boolean chatEnabled() {
        return chatEnabled;
    }

    public boolean chatAlwaysListen() {
        return chatAlwaysListen;
    }

    public String chatTrigger() {
        return chatTrigger;
    }

    public boolean speakInChat() {
        return speakInChat;
    }

    public int chatCooldownSeconds() {
        return chatCooldownSeconds;
    }

    public boolean chatLogToConsole() {
        return chatLogToConsole;
    }

    public int operateRadius() {
        return operateRadius;
    }

    public boolean allowBreakBlocks() {
        return allowBreakBlocks;
    }

    public boolean allowPlaceBlocks() {
        return allowPlaceBlocks;
    }

    public boolean allowAttack() {
        return allowAttack;
    }

    public boolean allowCreateBot() {
        return allowCreateBot;
    }

    public boolean requireLeavesForLogs() {
        return requireLeavesForLogs;
    }

    public int logLeafRadius() {
        return logLeafRadius;
    }

    public Set<Material> blockedMaterials() {
        return blockedMaterials;
    }

    public int navMaxNodes() {
        return navMaxNodes;
    }

    public int navMaxRange() {
        return navMaxRange;
    }

    public double navRepathDistance() {
        return navRepathDistance;
    }

    public boolean craftEnabled() {
        return craftEnabled;
    }

    public boolean craftRequireWorkbench() {
        return craftRequireWorkbench;
    }

    public int craftWorkbenchRadius() {
        return craftWorkbenchRadius;
    }

    public boolean idleWanderEnabled() {
        return idleWanderEnabled;
    }

    public int idleCheckIntervalSeconds() {
        return idleCheckIntervalSeconds;
    }

    public double idleWanderChance() {
        return idleWanderChance;
    }

    public double idleWanderRadius() {
        return idleWanderRadius;
    }

    public int idleWanderTimeoutSeconds() {
        return idleWanderTimeoutSeconds;
    }

    public boolean miningAutoPickup() {
        return miningAutoPickup;
    }

    public int miningPickupRadius() {
        return miningPickupRadius;
    }

    public boolean autoDefense() {
        return autoDefense;
    }

    public int defenseRadius() {
        return defenseRadius;
    }

    public boolean defendOwner() {
        return defendOwner;
    }

    public int defendOwnerRadius() {
        return defendOwnerRadius;
    }

    public int combatCheckIntervalTicks() {
        return combatCheckIntervalTicks;
    }

    public Set<EntityType> avoidMobs() {
        return avoidMobs;
    }

    public int avoidRadius() {
        return avoidRadius;
    }

    public boolean autoEat() {
        return autoEat;
    }

    public int eatThreshold() {
        return eatThreshold;
    }

    public int eatCheckIntervalSeconds() {
        return eatCheckIntervalSeconds;
    }

    /**
     * 血量低于或等于该值时强制进食；0 表示关闭。
     */
    public int emergencyEatHealth() {
        return emergencyEatHealth;
    }

    public boolean persistEnabled() {
        return persistEnabled;
    }

    public int persistIntervalSeconds() {
        return persistIntervalSeconds;
    }

    public boolean restoreOnStart() {
        return restoreOnStart;
    }

    public boolean astrBotEnabled() {
        return astrBotEnabled;
    }

    public String astrBotBaseUrl() {
        return astrBotBaseUrl;
    }

    public String astrBotKey() {
        return astrBotKey;
    }

    public String astrBotSessionMode() {
        return astrBotSessionMode;
    }

    public String astrBotFixedSession() {
        return astrBotFixedSession;
    }

    public int astrBotTimeoutSeconds() {
        return astrBotTimeoutSeconds;
    }

    public int astrBotMaxReplyChars() {
        return astrBotMaxReplyChars;
    }

    public String aiBackend() {
        return aiBackend;
    }

    public boolean teleportFallback() {
        return teleportFallback;
    }

    public boolean debug() {
        return debug;
    }

    public boolean logEnabled() {
        return logEnabled;
    }

    public boolean logCapturePayload() {
        return logCapturePayload;
    }

    public int logMaxBodyChars() {
        return logMaxBodyChars;
    }

    public int logRetainDays() {
        return logRetainDays;
    }

    public int logMaxFileMb() {
        return logMaxFileMb;
    }
}
