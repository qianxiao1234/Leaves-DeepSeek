package com.leavesdeepseek.buddy.ai;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.config.PlayerProfile;
import com.leavesdeepseek.buddy.config.PlayerProfiles;
import com.leavesdeepseek.buddy.config.PluginSettings;
import com.leavesdeepseek.buddy.util.Json;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * AI 配置解析中心。
 *
 * <p>把「玩家偏好 + 全局默认 + Key 来源 + 验证状态」合并成唯一的 {@link ResolvedAi}，
 * 所有上层代码都从这里拿结论，不再各自判断。</p>
 *
 * <p>选择规则（与用户要求一致）：</p>
 * <ol>
 *   <li>只配置了 DeepSeek（个人或全局 Key）→ 用 DeepSeek；</li>
 *   <li>只配置了 AstrBot → 用 AstrBot；</li>
 *   <li>两者都配置了 → {@link AiBackend#NEED_CHOICE}，提示玩家用
 *       {@code /buddy ai backend deepseek|astrbot} 选择；</li>
 *   <li>一个都没有 → {@link AiBackend#NONE}，并给出「缺什么」。</li>
 * </ol>
 */
public final class AiResolver {

    private static final String STATE_FILE = "global-state.yml";

    private final LeavesDeepSeekPlugin plugin;
    private final PluginSettings settings;
    private final PlayerProfiles profiles;

    /** 全局 Key 的验证结果缓存：fingerprint -> 验证时间戳（验证通过）或 0（失败）。 */
    private final java.util.Map<String, Long> globalValidation = new java.util.concurrent.ConcurrentHashMap<>();

    public AiResolver(LeavesDeepSeekPlugin plugin, PluginSettings settings, PlayerProfiles profiles) {
        this.plugin = plugin;
        this.settings = settings;
        this.profiles = profiles;
        loadGlobalState();
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析某个玩家实际应该使用的 AI。
     */
    public ResolvedAi resolve(Player player) {
        PlayerProfile profile = player == null ? null : profiles.getOrCreate(player);
        String preference = preferenceOf(profile);

        ApiConfig own = ownApi(profile);
        ApiConfig global = globalApi();
        AstrBotConfig astrBot = player == null ? globalAstrBot() : profiles.astrBotConfigFor(player);

        boolean deepSeekAvailable = own != null || global != null;
        boolean astrBotAvailable = astrBot != null && astrBot.usable();

        // 选定 DeepSeek 时优先使用玩家自己的 Key
        ApiConfig chosen = own != null ? own : global;
        ResolvedAi.KeySource source = own != null
                ? ResolvedAi.KeySource.OWN
                : (global != null ? ResolvedAi.KeySource.GLOBAL : ResolvedAi.KeySource.NONE);
        boolean validated = own != null
                ? profile != null && profile.isValidated()
                : (chosen != null && isGlobalValidated(chosen));

        AiBackend backend;
        String reason = "";
        switch (preference) {
            case "deepseek" -> {
                if (deepSeekAvailable) {
                    backend = AiBackend.DEEPSEEK;
                } else if (astrBotAvailable) {
                    backend = AiBackend.ASTRBOT;
                    reason = "你设置了优先使用 DeepSeek，但没有可用的 DeepSeek Key，已改用 AstrBot（纯对话）。";
                } else {
                    backend = AiBackend.NONE;
                    reason = "还没有可用的 AI：请用 /buddy ai set <apikey> 配置 DeepSeek Key，"
                            + "或让管理员接入 AstrBot。";
                }
            }
            case "astrbot" -> {
                if (astrBotAvailable) {
                    backend = AiBackend.ASTRBOT;
                } else if (deepSeekAvailable) {
                    backend = AiBackend.DEEPSEEK;
                    reason = "你设置了优先使用 AstrBot，但 AstrBot 不可用，已改用 DeepSeek。";
                } else {
                    backend = AiBackend.NONE;
                    reason = "AstrBot 不可用，也没有 DeepSeek Key：请用 /buddy ai set <apikey>，"
                            + "或让管理员用 /buddy global astrbot set 接入 AstrBot。";
                }
            }
            default -> {
                if (deepSeekAvailable && astrBotAvailable) {
                    backend = AiBackend.NEED_CHOICE;
                } else if (deepSeekAvailable) {
                    backend = AiBackend.DEEPSEEK;
                } else if (astrBotAvailable) {
                    backend = AiBackend.ASTRBOT;
                } else {
                    backend = AiBackend.NONE;
                    reason = "还没有可用的 AI：请用 /buddy ai set <apikey> 配置 DeepSeek Key，"
                            + "或让管理员用 /buddy global astrbot set 接入 AstrBot。";
                }
            }
        }

        return switch (backend) {
            case DEEPSEEK -> new ResolvedAi(AiBackend.DEEPSEEK, chosen, astrBot,
                    source, preference, validated, reason);
            case ASTRBOT -> new ResolvedAi(AiBackend.ASTRBOT, chosen, astrBot,
                    ResolvedAi.KeySource.ASTRBOT, preference, true, reason);
            case NEED_CHOICE -> ResolvedAi.needChoice(preference);
            case NONE -> ResolvedAi.none(reason);
        };
    }

    /**
     * 解析并确保 DeepSeek 配置已验证。AstrBot / 已用全局 Key 且验证过的情况会立即完成。
     */
    public CompletableFuture<ResolvedAi> resolveValidated(Player player) {
        ResolvedAi resolved = resolve(player);
        if (resolved.backend() != AiBackend.DEEPSEEK || resolved.validated()) {
            return CompletableFuture.completedFuture(resolved);
        }
        DeepSeekClient client = plugin.client();
        if (client == null) {
            return CompletableFuture.completedFuture(ResolvedAi.none("AI 客户端尚未初始化，请稍后再试。"));
        }
        ApiConfig api = resolved.api();
        return client.validate(api).handle((ignored, error) -> {
            if (error != null) {
                String message = friendlyValidationError(error);
                markValidated(player, resolved, false);
                plugin.getLogger().warning("[AI] " + resolved.keySource().label() + " 验证失败：" + message);
                return new ResolvedAi(AiBackend.NONE, resolved.api(), resolved.astrBotConfig(),
                        resolved.keySource(), resolved.preference(), false, message);
            }
            markValidated(player, resolved, true);
            return new ResolvedAi(resolved.backend(), resolved.api(), resolved.astrBotConfig(),
                    resolved.keySource(), resolved.preference(), true, "");
        });
    }

    /** 记录验证结果：个人 Key 写玩家配置，全局 Key 写 global-state.yml。 */
    public void markValidated(Player player, ResolvedAi resolved, boolean validated) {
        if (resolved == null) {
            return;
        }
        if (resolved.keySource() == ResolvedAi.KeySource.OWN && player != null && resolved.api() != null) {
            profiles.markValidated(profiles.getOrCreate(player), validated);
        } else if (resolved.keySource() == ResolvedAi.KeySource.GLOBAL && resolved.api() != null) {
            setGlobalValidated(resolved.api(), validated);
        }
    }

    private static String friendlyValidationError(Throwable error) {
        String raw = Json.rootMessage(error);
        if (raw == null || raw.isBlank()) {
            raw = "未知错误";
        }
        return "AI 配置验证失败：" + raw;
    }

    // ------------------------------------------------------------------
    // 偏好
    // ------------------------------------------------------------------

    /**
     * 生效的偏好：玩家显式设置优先，{@code default}/空 时使用服务器 {@code ai.backend}。
     */
    public String preferenceOf(PlayerProfile profile) {
        String raw = profile == null ? null : profile.getAiBackend();
        if (raw == null || raw.isBlank() || "default".equalsIgnoreCase(raw.trim())) {
            return normalize(settings.aiBackend());
        }
        return normalize(raw);
    }

    public void setPreference(Player player, String preference) {
        PlayerProfile profile = profiles.getOrCreate(player);
        profile.setAiBackend(normalize(preference));
        profiles.save(profile);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "auto";
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "deepseek", "ds", "default" -> "deepseek";
            case "astrbot", "ab" -> "astrbot";
            default -> "auto";
        };
    }

    // ------------------------------------------------------------------
    // Key 来源
    // ------------------------------------------------------------------

    /** 玩家自己的 DeepSeek 配置；没有 Key 时返回 null。 */
    public ApiConfig ownApi(PlayerProfile profile) {
        if (profile == null) {
            return null;
        }
        String key = profile.getApiKey();
        if (key == null || key.isBlank()) {
            return null;
        }
        String model = PlayerProfiles.isDefault(profile.getModel())
                ? settings.model() : profile.getModel().trim();
        String baseUrl = PlayerProfiles.isDefault(profile.getBaseUrl())
                ? settings.baseUrl() : PlayerProfiles.normalizeBaseUrl(profile.getBaseUrl());
        ApiConfig api = new ApiConfig(key.trim(), model, baseUrl);
        return api.usable() ? api : null;
    }

    /** 服务器全局 DeepSeek 配置；未配置或不允许回退时返回 null。 */
    public ApiConfig globalApi() {
        if (!settings.useGlobalConfig()) {
            return null;
        }
        String key = settings.apiKey();
        if (key == null || key.isBlank()) {
            return null;
        }
        ApiConfig api = new ApiConfig(key.trim(), settings.model(), settings.baseUrl());
        return api.usable() ? api : null;
    }

    /** 服务器全局 AstrBot 配置。 */
    public AstrBotConfig globalAstrBot() {
        return profiles.astrBotConfigFor(null);
    }

    /** 玩家是否可以直接用 DeepSeek（自己的 Key 或服务器全局 Key）。 */
    public boolean deepSeekAvailable(Player player) {
        ResolvedAi resolved = resolve(player);
        return resolved.backend() == AiBackend.DEEPSEEK
                || (resolved.backend() == AiBackend.NEED_CHOICE);
    }

    /** 玩家是否可以使用 AstrBot。 */
    public boolean astrBotAvailable(Player player) {
        if (player == null) {
            return false;
        }
        AstrBotConfig config = profiles.astrBotConfigFor(player);
        return config != null && config.usable();
    }

    // ------------------------------------------------------------------
    // 全局 Key 验证状态（持久化，避免每次启动都重新验证）
    // ------------------------------------------------------------------

    public boolean isGlobalValidated(ApiConfig api) {
        if (api == null) {
            return false;
        }
        Long at = globalValidation.get(fingerprint(api));
        return at != null && at > 0L;
    }

    public void setGlobalValidated(ApiConfig api, boolean validated) {
        if (api == null) {
            return;
        }
        globalValidation.put(fingerprint(api), validated ? System.currentTimeMillis() : 0L);
        saveGlobalState();
    }

    public void clearGlobalValidation() {
        globalValidation.clear();
        saveGlobalState();
    }

    private File stateFile() {
        return new File(plugin.getDataFolder(), STATE_FILE);
    }

    private void loadGlobalState() {
        File file = stateFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String fingerprint = yaml.getString("global-api.fingerprint", "");
        long validatedAt = yaml.getLong("global-api.validated-at", 0L);
        if (fingerprint != null && !fingerprint.isBlank() && validatedAt > 0L) {
            globalValidation.put(fingerprint, validatedAt);
        }
    }

    private void saveGlobalState() {
        YamlConfiguration yaml = new YamlConfiguration();
        globalValidation.forEach((fingerprint, at) -> {
            if (at > 0L) {
                yaml.set("global-api.fingerprint", fingerprint);
                yaml.set("global-api.validated-at", at);
            }
        });
        try {
            File folder = plugin.getDataFolder();
            if (!folder.exists() && !folder.mkdirs()) {
                return;
            }
            yaml.save(stateFile());
        } catch (IOException exception) {
            plugin.getLogger().warning("保存全局 AI 状态失败：" + exception.getMessage());
        }
    }

    /**
     * Key 指纹：只存摘要，不落盘明文 Key。
     */
    private static String fingerprint(ApiConfig api) {
        String raw = api.apiKey() + "|" + api.model() + "|" + api.baseUrl();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 12; index++) {
                builder.append(String.format("%02x", bytes[index]));
            }
            return builder.toString();
        } catch (Exception exception) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
