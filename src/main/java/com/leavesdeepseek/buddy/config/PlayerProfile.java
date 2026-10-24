package com.leavesdeepseek.buddy.config;

import java.util.UUID;

/**
 * 单个玩家的持久化配置。
 */
public final class PlayerProfile {

    private final UUID uuid;
    private volatile String name;

    private volatile String apiKey = "";
    private volatile String model = "default";
    private volatile String baseUrl = "default";
    private volatile boolean validated;
    private volatile long validatedAt;

    /** auto / deepseek / astrbot；null 表示跟随服务器默认 */
    private volatile String aiBackend;

    /** null 表示跟随服务器全局 AstrBot 配置 */
    private volatile Boolean astrBotEnabled;
    private volatile String astrBotBaseUrl = "";
    private volatile String astrBotKey = "";
    private volatile String astrBotSessionMode;
    private volatile String astrBotFixedSession;

    /** null 表示跟随服务器默认值 */
    private volatile Boolean autoFollow;
    private volatile Boolean autoDefense;
    private volatile Boolean defendOwner;
    private volatile Boolean idleWander;
    private volatile Boolean autoEat;

    public PlayerProfile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null || model.isBlank() ? "default" : model.trim();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "default" : baseUrl.trim();
    }

    public boolean isValidated() {
        return validated;
    }

    public void setValidated(boolean validated) {
        this.validated = validated;
    }

    public long getValidatedAt() {
        return validatedAt;
    }

    public void setValidatedAt(long validatedAt) {
        this.validatedAt = validatedAt;
    }

    public String getAiBackend() {
        return aiBackend;
    }

    public void setAiBackend(String aiBackend) {
        this.aiBackend = aiBackend == null || aiBackend.isBlank() ? null : aiBackend.trim().toLowerCase();
    }

    public Boolean getAstrBotEnabled() {
        return astrBotEnabled;
    }

    public void setAstrBotEnabled(Boolean astrBotEnabled) {
        this.astrBotEnabled = astrBotEnabled;
    }

    public String getAstrBotBaseUrl() {
        return astrBotBaseUrl;
    }

    public void setAstrBotBaseUrl(String astrBotBaseUrl) {
        String value = astrBotBaseUrl == null ? "" : astrBotBaseUrl.trim();
        if (value.equalsIgnoreCase("default")) {
            value = "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        this.astrBotBaseUrl = value;
    }

    public String getAstrBotKey() {
        return astrBotKey;
    }

    public void setAstrBotKey(String astrBotKey) {
        this.astrBotKey = astrBotKey == null ? "" : astrBotKey.trim();
    }

    public String getAstrBotSessionMode() {
        return astrBotSessionMode;
    }

    public void setAstrBotSessionMode(String astrBotSessionMode) {
        this.astrBotSessionMode = astrBotSessionMode == null
                || astrBotSessionMode.isBlank()
                || astrBotSessionMode.equalsIgnoreCase("default")
                ? null : astrBotSessionMode.trim().toLowerCase();
    }

    public String getAstrBotFixedSession() {
        return astrBotFixedSession;
    }

    public void setAstrBotFixedSession(String astrBotFixedSession) {
        this.astrBotFixedSession = astrBotFixedSession == null
                || astrBotFixedSession.isBlank()
                || astrBotFixedSession.equalsIgnoreCase("default")
                ? null : astrBotFixedSession.trim();
    }

    public Boolean getAutoFollow() {
        return autoFollow;
    }

    public void setAutoFollow(Boolean autoFollow) {
        this.autoFollow = autoFollow;
    }

    public Boolean getAutoDefense() {
        return autoDefense;
    }

    public void setAutoDefense(Boolean autoDefense) {
        this.autoDefense = autoDefense;
    }

    public Boolean getDefendOwner() {
        return defendOwner;
    }

    public void setDefendOwner(Boolean defendOwner) {
        this.defendOwner = defendOwner;
    }

    public Boolean getIdleWander() {
        return idleWander;
    }

    public void setIdleWander(Boolean idleWander) {
        this.idleWander = idleWander;
    }

    public Boolean getAutoEat() {
        return autoEat;
    }

    public void setAutoEat(Boolean autoEat) {
        this.autoEat = autoEat;
    }
}
