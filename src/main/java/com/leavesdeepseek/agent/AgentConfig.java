package com.leavesdeepseek.agent;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code config.yml} 里新增的 {@code agent:} 与 {@code body:} 两段配置。
 *
 * <p>传统假人的 {@code companion.*} / {@code chat.*} / {@code combat.*} 都不走这里。</p>
 */
public final class AgentConfig {

    private final JavaPlugin plugin;

    private boolean enabled;
    private String bind = "127.0.0.1";
    private int port = 8123;
    private String token = "";
    /** token 留空时用的默认值 = 插件名小写（用户要求：方便，需要时自己改）。 */
    public static final String DEFAULT_TOKEN = "leavesdeepseek";
    private boolean tokenDefault;
    private int maxBodiesPerSession = 1;
    private int authTimeoutSeconds = 5;
    private int maxFrameBytes = 65536;
    private int maxActionsPerSecond = 8;
    private int maxSensePerSecond = 4;
    private int maxConcurrentTasks = 2;
    private int chatCooldownMs = 2000;
    private int idleToPausedSeconds = 90;
    private int pausedToOfflineSeconds = 300;
    private boolean autoRespawn;
    private int respawnDelaySeconds;
    private boolean audit = true;

    /** 启动时是否把存档里的躯壳自动生成出来（false = 只登记成 offline，等 attach 再上线）。 */
    private boolean autoLoad = true;
    /** 躯壳死亡是否保留物品（true = 像传统假人一样，一件不掉）。 */
    private boolean keepInventory = true;

    /** AI 能不能通过 RPC 造躯壳（false 时只有 /ai body create 可用）。 */
    private boolean allowCreate = true;
    /** 躯壳能不能通过 RPC 删：own（只删自己造的，默认）/ all / false。 */
    private String allowDeleteMode = "own";
    /** create 不指定世界时用哪个世界（空 = 主世界）。 */
    private String createDefaultWorld = "";
    /** create 时把出生点抬高的格数（防卡在方块里）。 */
    private int createDefaultYOffset;
    /** 自定义躯壳名规则（空 = 用内置 3~16 位字母数字下划线）。 */
    private String bodyNameRegex = "";
    /** 造/删躯壳是否写审计。 */
    private boolean auditCreate = true;

    private int motionHz = 5;
    private int terrainHz = 2;
    private int companionHz = 1;
    private int taskHz = 5;
    private double closeRadius = 16.0;
    private double nearbyRadius = 48.0;
    private int landmarkMax = 8;
    private double chatRadius = 64.0;

    private int maxBodies = 3;
    private boolean allowPvp;
    private boolean allowContainer;
    private boolean allowTrade;
    private boolean allowDropItem = true;
    private double operateRadius = 48.0;
    private Set<Material> blockedMaterials = Collections.emptySet();
    private int autoEatThreshold = 14;
    private double tpsPauseThreshold = 15.0;
    private double tpsOfflineThreshold = 10.0;

    public AgentConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("agent.enabled", false);
        bind = normalizeBind(config.getString("agent.bind", "127.0.0.1"));
        port = clamp(config.getInt("agent.port", 8123), 1, 65535);
        token = normalizeToken(config.getString("agent.token", ""));
        maxBodiesPerSession = clamp(config.getInt("agent.max-bodies-per-session", 1), 1, 16);
        authTimeoutSeconds = clamp(config.getInt("agent.auth-timeout-seconds", 5), 1, 120);
        maxFrameBytes = clamp(config.getInt("agent.max-frame-bytes", 65536), 1024, 1024 * 1024);
        maxActionsPerSecond = clamp(config.getInt("agent.max-actions-per-second", 8), 1, 100);
        maxSensePerSecond = clamp(config.getInt("agent.max-sense-per-second", 4), 1, 100);
        maxConcurrentTasks = clamp(config.getInt("agent.max-concurrent-tasks", 2), 1, 8);
        chatCooldownMs = clamp(config.getInt("agent.chat-cooldown-ms", 2000), 0, 60000);
        idleToPausedSeconds = clamp(config.getInt("agent.idle-to-paused-seconds", 90), 5, 3600);
        pausedToOfflineSeconds = clamp(config.getInt("agent.paused-to-offline-seconds", 300), 5, 86400);
        autoRespawn = config.getBoolean("agent.auto-respawn", false);
        respawnDelaySeconds = clamp(config.getInt("agent.respawn-delay-seconds", 0), 0, 300);
        audit = config.getBoolean("agent.audit", true);
        autoLoad = config.getBoolean("agent.auto-load", true);
        keepInventory = config.getBoolean("agent.keep-inventory", true);

        allowCreate = config.getBoolean("agent.allow-create", true);
        allowDeleteMode = normalizeDeleteMode(config.get("agent.allow-delete"));
        createDefaultWorld = trim(config.getString("agent.create-default-world", ""));
        createDefaultYOffset = clamp(config.getInt("agent.create-default-y-offset", 0), 0, 64);
        bodyNameRegex = trim(config.getString("agent.body-name-regex", ""));
        auditCreate = config.getBoolean("agent.audit-create", true);

        motionHz = clamp(config.getInt("agent.sense.motion-hz", 5), 1, 20);
        terrainHz = clamp(config.getInt("agent.sense.terrain-hz", 2), 1, 20);
        companionHz = clamp(config.getInt("agent.sense.companion-hz", 1), 1, 20);
        taskHz = clamp(config.getInt("agent.sense.task-hz", 5), 1, 20);
        closeRadius = clamp(config.getDouble("agent.sense.close-radius", 16.0), 1.0, 128.0);
        nearbyRadius = clamp(config.getDouble("agent.sense.nearby-radius", 48.0), 2.0, 256.0);
        landmarkMax = clamp(config.getInt("agent.sense.landmark-max", 8), 0, 32);
        chatRadius = clamp(config.getDouble("agent.sense.chat-radius", 64.0), 1.0, 256.0);

        maxBodies = clamp(config.getInt("body.max-bodies", 3), 1, 64);
        allowPvp = config.getBoolean("body.allow-pvp", false);
        allowContainer = config.getBoolean("body.allow-container", false);
        allowTrade = config.getBoolean("body.allow-trade", false);
        allowDropItem = config.getBoolean("body.allow-drop-item", true);
        operateRadius = clamp(config.getDouble("body.operate-radius", 48.0), 4.0, 256.0);
        blockedMaterials = parseMaterials(config.getStringList("body.blocked-materials"));
        autoEatThreshold = clamp(config.getInt("body.auto-eat-threshold", 14), 0, 20);
        tpsPauseThreshold = clamp(config.getDouble("body.tps-pause-threshold", 15.0), 1.0, 20.0);
        tpsOfflineThreshold = clamp(config.getDouble("body.tps-offline-threshold", 10.0), 1.0, 20.0);
    }

    /**
     * 配置里 token 为空时用默认值（插件名小写，方便联调）；填了就用填的。
     */
    private String normalizeToken(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.isEmpty()) {
            tokenDefault = false;
            return value;
        }
        tokenDefault = true;
        return DEFAULT_TOKEN;
    }

    /** 当前 token 是否还是默认值（对公网开放时要提示用户改）。 */
    public boolean usingDefaultToken() {
        return tokenDefault;
    }

    private static String normalizeBind(String raw) {        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "127.0.0.1";
        }
        return value.equalsIgnoreCase("localhost") ? "127.0.0.1" : value;
    }

    private static Set<Material> parseMaterials(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptySet();
        }
        EnumSet<Material> result = EnumSet.noneOf(Material.class);
        for (String name : names) {
            if (name == null) {
                continue;
            }
            Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (material != null) {
                result.add(material);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * {@code allow-delete} 三态：{@code own}（默认，只能删自己造的）/ {@code all} / {@code false}。
     * 兼容旧写法：true → all，false → false。
     */
    private static String normalizeDeleteMode(Object raw) {
        if (raw == null) {
            return "own";
        }
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "all", "true", "yes", "on" -> "all";
            case "false", "no", "off", "none" -> "false";
            default -> "own";
        };
    }

    private static String trim(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean enabled() {
        return enabled;
    }

    public String bind() {
        return bind;
    }

    public int port() {
        return port;
    }

    public String token() {
        return token;
    }

    public int maxBodiesPerSession() {
        return maxBodiesPerSession;
    }

    public int authTimeoutSeconds() {
        return authTimeoutSeconds;
    }

    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    public int maxActionsPerSecond() {
        return maxActionsPerSecond;
    }

    public int maxSensePerSecond() {
        return maxSensePerSecond;
    }

    public int maxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    public int chatCooldownMs() {
        return chatCooldownMs;
    }

    public int idleToPausedSeconds() {
        return idleToPausedSeconds;
    }

    public int pausedToOfflineSeconds() {
        return pausedToOfflineSeconds;
    }

    public boolean autoRespawn() {
        return autoRespawn;
    }

    public int respawnDelaySeconds() {
        return respawnDelaySeconds;
    }

    public boolean audit() {
        return audit;
    }

    public boolean autoLoad() {
        return autoLoad;
    }

    public boolean keepInventory() {
        return keepInventory;
    }

    public boolean allowCreate() {
        return allowCreate;
    }

    /** {@code own} / {@code all} / {@code false}。 */
    public String allowDeleteMode() {
        return allowDeleteMode;
    }

    /** 兼容旧调用：只要不是 false 就允许（具体能不能删由 BodyManager 判归属）。 */
    public boolean allowDelete() {
        return !"false".equals(allowDeleteMode);
    }

    public String createDefaultWorld() {
        return createDefaultWorld;
    }

    public int createDefaultYOffset() {
        return createDefaultYOffset;
    }

    public String bodyNameRegex() {
        return bodyNameRegex;
    }

    public boolean auditCreate() {
        return auditCreate;
    }

    public int motionHz() {
        return motionHz;
    }

    public int terrainHz() {
        return terrainHz;
    }

    public int companionHz() {
        return companionHz;
    }

    public int taskHz() {
        return taskHz;
    }

    public double closeRadius() {
        return closeRadius;
    }

    public double nearbyRadius() {
        return nearbyRadius;
    }

    public int landmarkMax() {
        return landmarkMax;
    }

    public double chatRadius() {
        return chatRadius;
    }

    public int maxBodies() {
        return maxBodies;
    }

    public boolean allowPvp() {
        return allowPvp;
    }

    public boolean allowContainer() {
        return allowContainer;
    }

    public boolean allowTrade() {
        return allowTrade;
    }

    public boolean allowDropItem() {
        return allowDropItem;
    }

    public double operateRadius() {
        return operateRadius;
    }

    public Set<Material> blockedMaterials() {
        return blockedMaterials;
    }

    public int autoEatThreshold() {
        return autoEatThreshold;
    }

    public double tpsPauseThreshold() {
        return tpsPauseThreshold;
    }

    public double tpsOfflineThreshold() {
        return tpsOfflineThreshold;
    }
}
