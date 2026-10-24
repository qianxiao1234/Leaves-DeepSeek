package com.leavesdeepseek.buddy.config;

import com.leavesdeepseek.buddy.ai.AiBackend;
import com.leavesdeepseek.buddy.ai.ApiConfig;
import com.leavesdeepseek.buddy.ai.AstrBotConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家个人配置：plugins/LeavesDeepSeek/players/&lt;uuid&gt;/
 * （config.yml、persona.txt、bot-state.yml）。
 */
public final class PlayerProfiles {

    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final File directory;
    private final Map<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();

    public PlayerProfiles(JavaPlugin plugin, PluginSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.directory = new File(plugin.getDataFolder(), "players");
        if (!directory.exists() && !directory.mkdirs()) {
            plugin.getLogger().warning("无法创建玩家配置目录：" + directory.getAbsolutePath());
        }
    }

    public PlayerProfile getOrCreate(Player player) {
        // 假人（Bot）也实现 Player，绝不为假人创建 players/<uuid>/ 目录
        if (player instanceof org.leavesmc.leaves.entity.bot.Bot) {
            return new PlayerProfile(player.getUniqueId(),
                    player.getName() == null ? "?" : player.getName());
        }
        return getOrCreate(player.getUniqueId(), player.getName());
    }

    public PlayerProfile getOrCreate(UUID uuid, String name) {
        PlayerProfile cached = cache.get(uuid);
        if (cached != null) {
            if (name != null && !name.isBlank()) {
                cached.setName(name);
            }
            return cached;
        }

        File configFile = configFileOf(uuid);
        File legacyFile = new File(directory, uuid + ".yml");
        boolean existed = configFile.exists();
        YamlConfiguration yaml;
        if (existed) {
            yaml = YamlConfiguration.loadConfiguration(configFile);
        } else if (legacyFile.exists()) {
            // 旧版本的单文件配置自动迁移
            yaml = YamlConfiguration.loadConfiguration(legacyFile);
        } else {
            yaml = new YamlConfiguration();
        }
        PlayerProfile profile = new PlayerProfile(uuid, name == null ? "?" : name);
        profile.setApiKey(yaml.getString("api.key", ""));
        profile.setModel(yaml.getString("api.model", "default"));
        profile.setBaseUrl(yaml.getString("api.base-url", "default"));
        profile.setValidated(yaml.getBoolean("api.validated", false));
        profile.setValidatedAt(yaml.getLong("api.validated-at", 0L));
        profile.setAutoFollow(readBoolean(yaml, "bot.auto-follow"));
        profile.setAutoDefense(readBoolean(yaml, "bot.auto-defense"));
        profile.setDefendOwner(readBoolean(yaml, "bot.defend-owner"));
        profile.setIdleWander(readBoolean(yaml, "bot.idle-wander"));
        profile.setAutoEat(readBoolean(yaml, "bot.auto-eat"));
        profile.setAiBackend(yaml.getString("ai.backend", null));
        profile.setAstrBotEnabled(readBoolean(yaml, "astrbot.enabled"));
        profile.setAstrBotBaseUrl(yaml.getString("astrbot.base-url", ""));
        profile.setAstrBotKey(yaml.getString("astrbot.key", ""));
        profile.setAstrBotSessionMode(yaml.getString("astrbot.session-mode", null));
        profile.setAstrBotFixedSession(yaml.getString("astrbot.fixed-session", null));

        cache.put(uuid, profile);
        if (!existed) {
            save(profile);
        }
        return profile;
    }

    public void save(PlayerProfile profile) {
        if (profile == null) {
            return;
        }
        File file = configFileOf(profile.getUuid());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", profile.getName());
        yaml.set("api.key", profile.getApiKey());
        yaml.set("api.model", profile.getModel());
        yaml.set("api.base-url", profile.getBaseUrl());
        yaml.set("api.validated", profile.isValidated());
        yaml.set("api.validated-at", profile.getValidatedAt());
        yaml.set("bot.auto-follow", booleanValue(profile.getAutoFollow()));
        yaml.set("bot.auto-defense", booleanValue(profile.getAutoDefense()));
        yaml.set("bot.defend-owner", booleanValue(profile.getDefendOwner()));
        yaml.set("bot.idle-wander", booleanValue(profile.getIdleWander()));
        yaml.set("bot.auto-eat", booleanValue(profile.getAutoEat()));
        yaml.set("ai.backend", profile.getAiBackend() == null ? "default" : profile.getAiBackend());
        yaml.set("astrbot.enabled", booleanValue(profile.getAstrBotEnabled()));
        yaml.set("astrbot.base-url", profile.getAstrBotBaseUrl());
        yaml.set("astrbot.key", profile.getAstrBotKey());
        yaml.set("astrbot.session-mode", profile.getAstrBotSessionMode() == null
                ? "default" : profile.getAstrBotSessionMode());
        yaml.set("astrbot.fixed-session", profile.getAstrBotFixedSession());
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("保存玩家配置失败 " + file.getName() + "：" + exception.getMessage());
        }
    }

    public void reload() {
        cache.clear();
    }

    public File fileOf(UUID uuid) {
        return configFileOf(uuid);
    }

    /**
     * 每个玩家一个目录：players/&lt;uuid&gt;/
     */
    public File directoryOf(UUID uuid) {
        File dir = new File(directory, uuid.toString());
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("无法创建玩家目录：" + dir.getAbsolutePath());
        }
        return dir;
    }

    public File playersDirectory() {
        return directory;
    }

    public File configFileOf(UUID uuid) {
        return new File(directoryOf(uuid), "config.yml");
    }

    public File stateFileOf(UUID uuid) {
        return new File(directoryOf(uuid), "bot-state.yml");
    }

    public File personaFileOf(UUID uuid) {
        return new File(directoryOf(uuid), "persona.txt");
    }

    /**
     * 玩家自定义人格/附加提示词；没有设置时使用全局默认。
     * 优先级：players/&lt;uuid&gt;/persona.txt -> 插件目录 persona.txt -> config.yml 的 extra-system-prompt。
     */
    public String persona(Player player) {
        File file = personaFileOf(player.getUniqueId());
        if (file.exists()) {
            String text = readText(file);
            if (!text.isEmpty()) {
                return text;
            }
        }
        File globalPersona = globalPersonaFile();
        if (globalPersona.exists()) {
            String text = readText(globalPersona);
            if (!text.isEmpty()) {
                return text;
            }
        }
        String global = settings.extraSystemPrompt();
        return global == null ? "" : global.trim();
    }

    public File globalPersonaFile() {
        return new File(plugin.getDataFolder(), "persona.txt");
    }

    private String readText(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            plugin.getLogger().warning("读取人格配置失败 " + file.getName() + "：" + exception.getMessage());
            return "";
        }
    }

    public boolean hasCustomPersona(Player player) {
        File file = personaFileOf(player.getUniqueId());
        if (!file.exists()) {
            return false;
        }
        return !readText(file).isEmpty();
    }

    public void setPersona(Player player, String text) {
        File file = personaFileOf(player.getUniqueId());
        try {
            Files.writeString(file.toPath(), text == null ? "" : text.trim(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("保存人格配置失败：" + exception.getMessage());
        }
    }

    public void clearPersona(Player player) {
        File file = personaFileOf(player.getUniqueId());
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("删除人格配置失败：" + file.getAbsolutePath());
        }
    }

    public boolean isValidated(Player player) {
        return getOrCreate(player).isValidated();
    }

    public void markValidated(PlayerProfile profile, boolean validated) {
        profile.setValidated(validated);
        profile.setValidatedAt(validated ? System.currentTimeMillis() : 0L);
        save(profile);
    }

    public boolean autoFollow(Player player) {
        Boolean value = getOrCreate(player).getAutoFollow();
        return value != null ? value : settings.autoFollow();
    }

    public boolean autoDefense(Player player) {
        Boolean value = getOrCreate(player).getAutoDefense();
        return value != null ? value : settings.autoDefense();
    }

    public boolean defendOwner(Player player) {
        Boolean value = getOrCreate(player).getDefendOwner();
        return value != null ? value : settings.defendOwner();
    }

    public boolean idleWander(Player player) {
        Boolean value = getOrCreate(player).getIdleWander();
        return value != null ? value : settings.idleWanderEnabled();
    }

    public boolean autoEat(Player player) {
        Boolean value = getOrCreate(player).getAutoEat();
        return value != null ? value : settings.autoEat();
    }

    /**
     * 解析玩家实际使用的 AstrBot 配置：玩家自己的优先，没填的项回退到服务器全局默认。
     */
    public AstrBotConfig astrBotConfigFor(Player player) {
        PlayerProfile profile = player == null ? null : getOrCreate(player);
        boolean enabled = profile != null && profile.getAstrBotEnabled() != null
                ? profile.getAstrBotEnabled()
                : settings.astrBotEnabled();
        String baseUrl = firstNonBlank(profile == null ? "" : profile.getAstrBotBaseUrl(),
                settings.astrBotBaseUrl());
        String key = firstNonBlank(profile == null ? "" : profile.getAstrBotKey(),
                settings.astrBotKey());
        String mode = firstNonBlank(profile == null ? "" : profile.getAstrBotSessionMode(),
                settings.astrBotSessionMode());
        String fixed = firstNonBlank(profile == null ? "" : profile.getAstrBotFixedSession(),
                settings.astrBotFixedSession());
        return new AstrBotConfig(enabled, baseUrl, key, mode, fixed,
                settings.astrBotTimeoutSeconds(), settings.astrBotMaxReplyChars());
    }

    /** 玩家是否使用了自己的 AstrBot Key（而不是服务器全局 Key）。 */
    public boolean hasOwnAstrBot(Player player) {
        return player != null && getOrCreate(player).getAstrBotKey() != null
                && !getOrCreate(player).getAstrBotKey().isBlank();
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    public static boolean isDefault(String value) {
        return value == null || value.isBlank() || value.trim().equalsIgnoreCase("default");
    }

    public static String normalizeBaseUrl(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static Boolean readBoolean(YamlConfiguration yaml, String path) {
        String raw = yaml.getString(path, "default");
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "true", "on", "yes" -> Boolean.TRUE;
            case "false", "off", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String booleanValue(Boolean value) {
        return value == null ? "default" : value.toString();
    }
}
